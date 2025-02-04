package ix.core.search;

import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.springframework.hateoas.server.EntityLinks;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import gsrs.controller.hateoas.GsrsLinkUtil;
import gsrs.controller.hateoas.IxContext;
import gsrs.springUtils.StaticContextAccessor;
import ix.core.models.FieldedQueryFacet;
import ix.core.models.FieldedQueryFacet.MATCH_TYPE;
import ix.utils.Util;
//TODO katzelda October 2020 : caching in a later sprint
//@CacheStrategy(evictable=false)
public class SearchResultContext {
    public enum Status {
        Pending,	//show  +
        Running,	//show  +
        Determined, //don't show +
        Done,		//don't show +
        Failed		//don't show +
    }
    
    //This also needs some canonical form of the standard query,
    //so that it COULD be rerun if it is ever removed / finished
    
    public static final BiFunction<SearchRequest, SearchResultContext, SearchResult> DEFAULT_ADAPTER = (sr, ctx)->{
    	return SearchResult.fromContext(ctx, sr.getOptions());
    };
    
    private BiFunction<SearchRequest, SearchResultContext, SearchResult> adapter = DEFAULT_ADAPTER;

    public static interface StatusChangeListener{
    	void onStatusChange(SearchResultContext.Status newStatus, SearchResultContext.Status oldStatus);
    }

    private List<SoftReference<StatusChangeListener>> listeners = new ArrayList<>();

    private SearchResultContext.Status _status = Status.Pending;
    private String mesg;
    private Long start;
    private Long stop;

    private Collection<Object> results = new LinkedBlockingDeque<>();

    @JsonIgnore
    private List<?> sponsoredResults = new ArrayList<>();

    @JsonIgnore
    private final List<FieldedQueryFacet> fieldFacets=new ArrayList<>();

    private String id = Util.randvar (10);
    private Integer total;
    private String key;


	private String originalRequest = null;

    public static class SearchResultContextDeterminedFuture extends FutureTask<Void>{
    	public SearchResultContextDeterminedFuture(final SearchResultContext context){
    		super(new WaitForDeterminedCallable(context));
    	}
    }

    private static class WaitForDeterminedCallable implements Callable<Void>, SearchResultContext.StatusChangeListener{
    	private SearchResultContext context;
    	private final CountDownLatch latch;

    	public WaitForDeterminedCallable(final SearchResultContext context){
    		Objects.requireNonNull(context);
    		this.context = context;
    		this.context.addListener(this);
            latch = new CountDownLatch(1);
    	}

    	public void onStatusChange(SearchResultContext.Status newStatus, SearchResultContext.Status oldStatus){
    		if(newStatus == Status.Determined ||
    			newStatus== Status.Done ||
    			newStatus == Status.Failed){

    			while(latch.getCount()>0){
                    latch.countDown();
                }
    		}

    	}
		@Override
		public Void call() throws Exception {
			if(latch.getCount()>0 && !context.isDetermined()){
				latch.await();
			}
			context.removeListener(this);
			return null;
		}
    }

    public SearchResultContext () {
    	//TODO: This assumption isn't always correct, use application.host instead
        try{
//            getEffectiveAdaptedURI
            IxContext ixc =StaticContextAccessor.getBean(IxContext.class);
            
            
            this.setGeneratingUrl(ixc.getEffectiveAdaptedURI().toString());
        }catch(Exception e){
            this.setGeneratingUrl(null);
        }
    }

    public SearchResultContext (SearchResult result) {
    	this();
    	if(result.getFieldFacets()!=null){
    		fieldFacets.addAll(result.getFieldFacets());
    	}
        setStart(result.getTimestamp());

        if (result.finished()) {
            setStatus(Status.Done);
            setStop(result.getStopTime());
        }else if (result.size() > 0){
        	setStatus(Status.Determined);
        }

        if (_status != Status.Done) {
            mesg = String.format
                ("Loading...%1$d%%",
                 (int)(Math.ceil(100.*result.size()/((double)result.count()))));
        }

        results = result.getMatches();
        total = result.count();
        this.key=result.getKey();

        sponsoredResults =result.getSponsoredMatches();
    }

    /**
     * Returns the set of records which matched a predefined
     * set of fields directly
     * @return
     */
    @JsonIgnore
    public List getExactMatches(){
        return sponsoredResults;
    }

    /**
     * Returns query narrowing suggestions and their counts
     * @return
     */
    @JsonIgnore
    public List<FieldedQueryFacet> getFieldFacets(){
    	return fieldFacets;
    }

    /**
     * Returns true if getExactMatches() would return a list of size >0
     * @return
     */
    @JsonIgnore
    public boolean hasExactMatches(){
    	return sponsoredResults.size()>0;
    }


    /**
     * Returns the suggested FieldQueryFacets grouped by match type,
     * in order they show in easy grouping
     * @return
     */
    @JsonIgnore
    public LinkedHashMap<MATCH_TYPE, List<FieldedQueryFacet>> getFieldFacetsMap(){
    	Map<MATCH_TYPE,Integer> place= new HashMap<MATCH_TYPE,Integer>();
    	place.put(MATCH_TYPE.FULL, 0);
    	place.put(MATCH_TYPE.WORD_STARTS_WITH, 1);
    	place.put(MATCH_TYPE.WORD, 2);
    	place.put(MATCH_TYPE.CONTAINS, 3);
    	place.put(MATCH_TYPE.NO_MATCH, 4);

    	LinkedHashMap<MATCH_TYPE, List<FieldedQueryFacet>> grouped = new LinkedHashMap<>();

    	fieldFacets.stream()
    			   .collect(Collectors.groupingBy(FieldedQueryFacet::getMatchType,Collectors.toList()))
    			   .entrySet()
    			   .stream()
    			   .sorted((e1,e2)->-(place.get(e2.getKey())-place.get(e1.getKey())))
    			   .forEach(e1->{
    				   grouped.put(e1.getKey(), e1.getValue());
    			   });
    	return grouped;

    }

    public String getId () { return id; }
    public SearchResultContext.Status getStatus () { return _status; }
    public void setStatus (SearchResultContext.Status status) {
    	SearchResultContext.Status ostat=this._status;
    	this._status = status;
    	notifyChange(_status,ostat);

    }
    public String getMessage () { return mesg; }
    public void setMessage (String mesg) { this.mesg = mesg; }
    public Integer getCount () { return results.size(); }
    public Integer getTotal () {
    	if(total !=null){
    		return total;
    	}else{
    		if(isDetermined()){
    			return results.size();
    		}
    	}
    	return null;
    }
    public void setTotal (int i) {
    	total = i;
    }

    public Long getStart () { return start; }
    public Long getStop () { return stop; }

    public boolean isFinished () {
        return _status == Status.Done || _status == Status.Failed;
    }

    public boolean isDetermined () {
        return isFinished () || _status == Status.Determined;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Collection getResults () { return results; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public List getResultsAsList() {
        if(results instanceof List)return (List)results;
        return (results != null) ? new ArrayList<>(results) : new ArrayList<>();
    }

    public void add (Object obj) { results.add(obj); }


    public void addListener(SearchResultContext.StatusChangeListener listener){
    	listeners.add(new SoftReference<>(listener));
    }

    public void removeListener(SearchResultContext.StatusChangeListener listener){
    	Iterator<SoftReference<StatusChangeListener>> iter =listeners.iterator();
    	while(iter.hasNext()){
    		SoftReference<StatusChangeListener> l = iter.next();
    		SearchResultContext.StatusChangeListener actualListener = l.get();
    		//if get() returns null then the object was garbage collected
    		if(actualListener ==null || listener.equals(actualListener)){
    			iter.remove();
    			//keep checking in the unlikely event that
    			//a listener was added twice?
    		}
    	}
    }

    private void notifyChange(SearchResultContext.Status newStatus, SearchResultContext.Status oldStatus){
    	Iterator<SoftReference<StatusChangeListener>> iter = listeners.iterator();
        List<StatusChangeListener> tocall = new ArrayList<>();
        while(iter.hasNext()){
        	SearchResultContext.StatusChangeListener l = iter.next().get();
            if(l ==null){
                iter.remove();
            }else{
            	tocall.add(l);
            }
        }
        tocall.forEach(l -> l.onStatusChange(newStatus, oldStatus));
    }
    public void setKey(String key){
    	this.key=key;
    }
    public String getKey(){
    	return key;
    }

    /**
     * Get a future which will return only when
     * {@link #isDetermined()}} is true.
     *
     *
     * @return a Future will never be null, but get() will return null when completed
     */
    @JsonIgnore
    public Future<Void> getDeterminedFuture(){
    	SearchResultContext.SearchResultContextDeterminedFuture future= new SearchResultContextDeterminedFuture(this);
        ForkJoinPool.commonPool().submit(future);
        return future;
    }
    
    public String toJson(){
    	ObjectMapper om = new ObjectMapper();
    	return om.valueToTree(this).toString();
    }
    
    
    
    //TODO: rewrite this to allow moving to core

    //TODO: TP: this must be part of bigger Link code refactoring concerning URLs
    //which are handled in a few different ways now.
    @JsonProperty("url")
    public String getUrl(){
        if(this.getKey()!=null) {
            
            EntityLinks entityLinks=StaticContextAccessor.getBean(EntityLinks.class);
            return GsrsLinkUtil.adapt(this.getKey(),entityLinks.linkFor(SearchResultContext.class).slash("(" + this.getKey() + ")").withSelfRel()).getHref();
        }
		return null;
    }
    //TODO katzelda October 2020: the Play version the result was saved in cache should we rethink this?
	// TP August 2021: this is still needed, because it's how asynchronous URLs are made
	// otherwise you can't easily get focused results 
    @JsonProperty("results")
    public String getResultUrl(){
        //This may not be ideal, we'll see
    	return getUrl() + "/results";
    }

    @JsonProperty("generatingUrl")
    public String getGeneratingUrl(){
    	return originalRequest ;
    }
    
    @JsonProperty("generatingUrl")
    public void setGeneratingUrl(String url){
    	this.originalRequest=url;
    }
	//TODO katzelda October 2020 : comment out getting result call for now since we don't implement that yet
	/*
    @JsonIgnore
    public Call getResultCall(){
    	return getResultCall(10, 0, 10, "");
    }

    @JsonIgnore
    public Call getCall(){
    	return getCall(10, 0, 10, "");
    }

    public Call getResultCall(int top, int skip, int fdim, String field){
//		return ix.core.controllers.search.routes.SearchFactory.getSearchResultContextResults(this.getKey(), top, skip, fdim, field);


    }
    
    */
    
    public SearchResultContext getFocused(int top, int skip, int fdim, String field){
    	return new FocusedSearchResultContext(this,top,skip,fdim, field);
    }
    
//    @JsonIgnore
//    public Call getCall(int top, int skip, int fdim, String field){
//    	return ix.core.controllers.search.routes.SearchFactory.getSearchResultContext(this.getKey(),top, skip, fdim, field);
//    }
    
    
    
    /**
     * Sets the adapter to be used when fetching records from his context.
     * This is used to set special collection, indexing and grouping considerations,
     * typically by passing the result set through a lucene index. This call will only
     * have an effect if the current adapter is not {@link #DEFAULT_ADAPTER}. That is,
     * it is intended to be set only once. 
     * 
     * TODO: move to constructor or builder
     * @param adapter
     */
    public void setAdapter(BiFunction<SearchRequest, SearchResultContext, SearchResult> adapter){
        if(this.adapter==DEFAULT_ADAPTER){
    		this.adapter=adapter;
    	}
    }
    
    public SearchResult getAdapted(SearchRequest opt){
    	return adapter.apply(opt, this);
    }
    
//    @Deprecated
//    public static SearchResultContext getSearchResultContextForKey(String key){
//    	SearchResultContextOrSerialized ctxwrapped=getContextForKey(key);
//    	if(ctxwrapped==null)return null;
//    	return ctxwrapped.getContext();
//    }
    
    public static class SearchResultContextOrSerialized{
    	SearchResultContext ctx=null;
    	SerailizedSearchResultContext serial=null;
    	public SearchResultContextOrSerialized(SearchResultContext ctx){
    		this.ctx=ctx;
    		this.serial=ctx.getSerializedForm();
    	}
    	public SearchResultContextOrSerialized(SerailizedSearchResultContext serial){
    		this.serial=serial;
    	}
    	
    	public SearchResultContext getContext(){
    		return this.ctx;
    	}
    	
    	//Should never be null
    	public SerailizedSearchResultContext getSerialized(){
    		return this.serial;
    	}
    	
    	public boolean hasFullContext(){
    		return ctx!=null;
    	}
    }
    

	public void setStart(Long start) {
		this.start = start;
	}

	public void setStop(Long stop) {
		this.stop = stop;
	}
	
	
	@JsonIgnore
	public SerailizedSearchResultContext getSerializedForm(){
		return new SerailizedSearchResultContext(this);
	}
	
	/**
	 * A Serialized form of the {@link SearchResultContext}
	 * the idea is to have a very minimal form that can
	 * be used to re-constitute the context when needed.
	 * 
	 * <p>
	 * This does not help cache the actual results,
	 * it is just meant to preserve the recoverability
	 * of a keyed {@link SearchResultContext}. This is
	 * similar to the idea of the ETag
	 * </p>
	 * 
	 * <p>
	 * TODO: integrate this with the ETag
	 * probably retiring this completely.
	 * </p>
	 * 
	 * @author peryeata
	 *
	 */
	public static class SerailizedSearchResultContext implements Serializable{
		private static final long serialVersionUID = 1L;
		public String generatingPath;
		public String key;
		public String id;
		
		public SerailizedSearchResultContext(){
			
		}
		
		public SerailizedSearchResultContext(SearchResultContext ctx){
			this.generatingPath=ctx.getGeneratingUrl();
			this.key=ctx.getKey();
			this.id=ctx.getId();
		}
		
		/**
		 * Returns a key to use for storing this object.
		 * This key is computable from a {@link SearchResultContext}
		 * key, and is equivalent to calling 
		 * {@link SearchResultContext#getSerializedKey(String)}
		 * with the key from the {@link SearchResultContext}.
		 * 
		 * @return
		 */
		public String getSerializedKey(){
			return SearchResultContext.getSerializedKey(key);
		}
	}  
	
	/**
	 * Returns a modified form of a {@link SearchResultContext}
	 * key, for use in retrieving and storing a 
	 * {@link SerailizedSearchResultContext}
	 * @param okey
	 * @return
	 */
	public static String getSerializedKey(String okey){
		return "cached/" + okey;
	}
}