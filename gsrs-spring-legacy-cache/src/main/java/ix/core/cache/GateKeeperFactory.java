package ix.core.cache;

import ix.utils.Util;
import net.sf.ehcache.*;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import net.sf.ehcache.writer.CacheWriter;
import net.sf.ehcache.writer.writebehind.operations.SingleOperationType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Created by katzelda on 5/26/16.
 */
public final class GateKeeperFactory {

    private static final String IX_CACHE_EVICTABLE = "IxCache-Evictable";
    private static final String IX_CACHE_NOT_EVICTABLE = "IxCache-Not-Evictable";


    private Supplier<GateKeeper> supplier;

    private GateKeeperFactory(Supplier<GateKeeper> supplier){
        this.supplier = supplier;
    }

    public GateKeeper create(){
        return supplier.get();
    }

    public enum DoNothingDBCacheWriter implements GinasFileBasedCacheAdapter{
    	INSTANCE;
    	
		@Override
		public Object createEntry(Object arg0) throws Exception {
			//System.out.println("Generating:" + arg0);
			return null;
		}

		@Override
		public CacheWriter clone(Ehcache arg0)
				throws CloneNotSupportedException {
			throw new CloneNotSupportedException();
		}

		@Override
		public void delete(CacheEntry arg0) throws CacheException {
			//System.out.println("Deleting:" + arg0);
		}

		@Override
		public void deleteAll(Collection<CacheEntry> arg0)
				throws CacheException {
			//System.out.println("Deleting all");
		}

		@Override
		public void dispose() throws CacheException {
		}

		@Override
		public void init() {}

		@Override
		public void throwAway(Element arg0, SingleOperationType arg1,
				RuntimeException arg2) {
			//System.out.println("Throwing away:" + arg0);
		}

		@Override
		public void write(Element arg0) throws CacheException {
			// TODO Auto-generated method stub
			//System.out.println("Writing:" + arg0.getKey());
		}

		@Override
		public void writeAll(Collection<Element> arg0) throws CacheException {
			// TODO Auto-generated method stub
			
		}
    	
    }

    public static class Builder{
        private int debugLevel = 2;

        private final int maxElements, timeToLive, timeToIdle;
        private Integer nonEvictableMaxElements, nonEvictableTimeToLive, nonEvictableTimeToIdle;

        private GinasFileBasedCacheAdapter cacheAdapter = DoNothingDBCacheWriter.INSTANCE;
        private KeyMaster km= new ExplicitMapKeyMaster();
        
        public Builder(int maxElements, int timeToLive, int timeToIdle){
            this.maxElements = maxElements;
            this.timeToIdle = timeToIdle;
            this.timeToLive = timeToLive;

        }

        public Builder debugLevel(int level){
            this.debugLevel = level;
            return this;
        }
        
        public Builder cacheAdapter(GinasFileBasedCacheAdapter adapter){
        	this.cacheAdapter=adapter;
        	return this;
        }
        
        public Builder keyMaster(KeyMaster keymaster){
        	this.km=keymaster;
        	return this;
        }


        public Builder useNonEvictableCache(int maxElements, int timeToLive, int timeToIdle){
            this.nonEvictableMaxElements = maxElements;
            this.nonEvictableTimeToLive = timeToLive;
            this.nonEvictableTimeToIdle = timeToIdle;
            return this;
        }

        public GateKeeperFactory build(){
            Supplier<GateKeeper> supplier;
            if(nonEvictableMaxElements ==null){
                //single cache

                supplier = ()->{

                    Cache evictableCache = new Cache( new CacheConfiguration()
                            .name(IX_CACHE_NOT_EVICTABLE)
                            //.maxBytesLocalHeap(maxElements, MemoryUnit.MEGABYTES)
                            .maxEntriesLocalHeap(10)
                            .timeToLiveSeconds(timeToLive)
                            .timeToIdleSeconds(timeToIdle));
                    CacheManager.getInstance().removeCache(evictableCache.getName());
                    CacheManager.getInstance().addCache(evictableCache);

                    evictableCache.registerCacheWriter(cacheAdapter);
                    //TODO how do we turn on caching in newer version?
//                    evictableCache.setSampledStatisticsEnabled(true);
                    Ehcache eh_evictableCache= new SelfPopulatingCache(evictableCache,cacheAdapter);
                    return new SingleCacheGateKeeper(debugLevel, this.km, eh_evictableCache);
               };
            }else{
                supplier = ()->{
                    Cache evictableCache = new Cache(new CacheConfiguration()
                            .name(IX_CACHE_EVICTABLE)
                            .maxEntriesLocalHeap(maxElements)
                            .timeToLiveSeconds(timeToLive)
                            .timeToIdleSeconds(timeToIdle));
                    
                    evictableCache.registerCacheWriter(cacheAdapter);
                    Ehcache eh_evictableCache= new SelfPopulatingCache(evictableCache,cacheAdapter);
                    Cache nonEvictableCache = new Cache ( new CacheConfiguration()
                            .name(IX_CACHE_NOT_EVICTABLE)
                            .maxEntriesLocalHeap(nonEvictableMaxElements)
                            //.maxBytesLocalHeap(nonEvictableMaxElements, MemoryUnit.MEGABYTES)
                            .timeToLiveSeconds(nonEvictableTimeToLive)
                           // .sizeOfPolicy((new SizeOfPolicyConfiguration()).maxDepth(0).maxDepthExceededBehavior(SizeOfPolicyConfiguration.MaxDepthExceededBehavior.CONTINUE))
                            .timeToIdleSeconds(nonEvictableTimeToIdle));
                    nonEvictableCache.registerCacheWriter(cacheAdapter);

                    Ehcache eh_nonEvictableCache= new SelfPopulatingCache(nonEvictableCache,cacheAdapter);

                    CacheManager.getInstance().removeCache(evictableCache.getName());
                    CacheManager.getInstance().addCache(evictableCache);


                    CacheManager.getInstance().removeCache(nonEvictableCache.getName());
                    CacheManager.getInstance().addCache(nonEvictableCache);

                    //TODO how do we turn on caching in newer version?
//                    evictableCache.setSampledStatisticsEnabled(true);

                    return new TwoCacheGateKeeper(debugLevel, this.km, eh_evictableCache, eh_nonEvictableCache);
                };
            }

            return new GateKeeperFactory(supplier);
        }
    }

    
    private static class ExplicitMapKeyMaster implements KeyMaster{
        private ConcurrentHashMap<String,Set<String>> thekeys= new ConcurrentHashMap<String,Set<String>>();
        private int size=0;
        public Set<String> getAllAdaptedKeys(String baseKey){
            return thekeys.get(baseKey);
        }

        @Override
        public void addKey(String baseKey, String adaptKey) {
            if(thekeys.computeIfAbsent(baseKey, k-> new HashSet<>()).add(adaptKey)){
                size++;
            }
        }

        @Override
        public void removeKey(String baseKey, String adaptKey) {
            Set<String> keylist=thekeys.get(baseKey);
            if(keylist!=null){
                if(keylist.remove(adaptKey)){
                    size--;
                }
            }
        }

        @Override
        public void removeAll() {
            thekeys.clear();
        }
        
        public String adaptKey(String baseKey) {
            final String user = getActingUser();
            return "!" + baseKey + "#" + Util.sha1(user);
        }
        private String getActingUser(){
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if(authentication !=null) {
                Object principal = authentication.getPrincipal();
                if (principal != null) {
                    if (principal instanceof UserDetails) {
                        return ((UserDetails) principal).getUsername();
                    } else {
                        return authentication.getName();
                    }
                }
            }
            return "GUEST";
        }

        public String unAdaptKey(String adaptedKey) {
            if (!adaptedKey.startsWith("!")) {
                return adaptedKey;
            }
            return adaptedKey.substring(1, adaptedKey.lastIndexOf('#'));
        }
    }
}
