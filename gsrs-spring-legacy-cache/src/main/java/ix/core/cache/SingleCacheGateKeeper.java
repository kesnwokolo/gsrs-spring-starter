package ix.core.cache;

import ix.utils.CallableUtil.TypedCallable;
import lombok.extern.slf4j.Slf4j;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.statistics.CoreStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Created by katzelda on 5/19/16.
 */
@Slf4j
public class SingleCacheGateKeeper implements GateKeeper {


    private final KeyMaster keyMaster;
    private final Ehcache evictableCache;

    private volatile boolean isClosed;


    private final int debugLevel;

    public SingleCacheGateKeeper(int debugLevel, KeyMaster keyMaster, Ehcache evictableCache){
        Objects.requireNonNull(keyMaster);
        Objects.requireNonNull(evictableCache);

        this.debugLevel = debugLevel;
        this.keyMaster = keyMaster;
        this.evictableCache = evictableCache;

    }

    @Override
    public void clear() {
        keyMaster.removeAll();
        evictableCache.removeAll();
    }

    private <T> TypedCallable<T> createRaw(TypedCallable<T> delegate, String key){

        return createRaw(delegate, key, 0);
    }

    private <T> TypedCallable<T> createRaw(TypedCallable<T> delegate, String key, int seconds){

        return new CacheGeneratorWrapper<T>(delegate, key, key,seconds);
    }

    private <T> TypedCallable<T> createKeyWrapper(TypedCallable<T> delegate, String key, String adaptedKey){

        return createKeyWrapper(delegate, key, adaptedKey, 0);
    }

    private <T> TypedCallable<T> createKeyWrapper(TypedCallable<T> delegate, String key, String adaptedKey, int seconds){

        return new CacheGeneratorWrapper<T>(delegate, key, adaptedKey,seconds);
    }

    @Override
    public boolean remove(String key) {
        String adaptedKey = keyMaster.adaptKey(key);
        return removeRaw(adaptedKey);
    }

    private boolean removeRaw(String adaptedKey) {
        return evictableCache.remove(adaptedKey);
    }

    @Override
    public boolean removeAllChildKeys(String key) {
        Set<String> set = keyMaster.getAllAdaptedKeys(key);
        if(set ==null || set.isEmpty()){
            return false;
        }
        set.stream()
                .forEach(k-> removeRaw(k));

        return true;
    }

    @Override
    public Stream<Element> elements(int top, int skip) {
        return evictableCache.getKeys().stream()
                                        .skip(skip)
                                        .limit(top)
                                        .map(key -> evictableCache.get(key));

    }

    /**
     * Wraps a generateor that creates the actual thing to cache
     * and puts it in the correct cache with the correct adapted key.
     * @param <T>
     */
    private class CacheGeneratorWrapper<T> implements TypedCallable<T>{
       private final TypedCallable<T> delegate;
       private final String key, adaptedKey;

       private final int seconds;




       private CacheGeneratorWrapper(TypedCallable<T> delegate, String key, String adaptedKey, int seconds) {
           this.delegate = delegate;
           this.key = key;
           this.adaptedKey = adaptedKey;
           this.seconds = seconds;
       }

       @Override
       public T call() throws Exception {
           T t = delegate.call();
           keyMaster.addKey(key, adaptedKey);
           addToCache(adaptedKey, t, seconds);
           return t;
       }
   }

    @Override
    public List<CoreStatistics> getStatistics() {
    	List<CoreStatistics> statlist=new ArrayList<>();
    	statlist.add(evictableCache.getStatistics().getCore());
        return statlist;
    }

    @Override
    public <T> T getSinceOrElse(String key, long creationTime, TypedCallable<T> generator) throws Exception{
        return getSinceOrElse(key, creationTime, generator, 0);
    }
    
    
    private <T> T getSinceOrElse(String key, long creationTime, TypedCallable<T> generator, int seconds) throws Exception{
      String adaptedKey = keyMaster.adaptKey(key);
        return getOrElseRaw(adaptedKey,
                createKeyWrapper(generator, key, adaptedKey, seconds),
                e->e.getCreationTime() < creationTime
                );
    }



    private  <T> T getOrElseRaw(String key, TypedCallable<T> generator, Predicate<Element> regeneratePredicate) throws Exception{
        Element e = evictableCache.get(key);


        if(e ==null || e.getObjectValue() ==null || regeneratePredicate.test(e)){
            if (debugLevel >= 2) {
                log.debug("IxCache missed: " + key);
            }
            return generator.call();
        }
        try {
            return (T) e.getObjectValue();
        }catch(Exception ex){
            //in case there is a cast problem
            //or some other problem with the cached value
            //re-generate
            return generator.call();
        }

    }

    public <T> T getSinceOrElseRaw(String key, long creationTime,
            TypedCallable<T> generator) throws Exception {
        return getOrElseRaw(key,
                   createRaw(generator,key, 0),
                   e->e.getCreationTime() < creationTime);
    }
    

	private <T> T getOrElseRaw(String key, long creationTime,
			TypedCallable<T> generator, int seconds) throws Exception {
		  return getOrElseRaw(key,
	               createRaw(generator,key, seconds),
	               e->e.getCreationTime() < creationTime);
	}

    private <T> T getOrElseRaw(String key, TypedCallable<T> generator, int seconds) throws Exception{
       return getOrElseRaw(key,
               createRaw(generator,key, seconds),
               (e)->false);
    }


    @Override
    public Object get(String key){
        return getRaw(keyMaster.adaptKey(key));
    }


    public Element getRawElement(String key){
        Element e = evictableCache.get(key);

        return e;
    }
    @Override
    public Object getRaw(String key){
        Element e = getRawElement(key);

        if(e ==null ){
            return null;
        }
        return e.getObjectValue();
    }

    private <T> T getOrElse(String key, TypedCallable<T> generator, int seconds) throws Exception{
        String adaptedKey = keyMaster.adaptKey(key);

        return getOrElseRaw(adaptedKey,
                createKeyWrapper(generator, key, adaptedKey, seconds),
                e-> false);
    }
    
    

    @Override
    public void put(String key, Object value, int expiration){
        String adaptedKey = keyMaster.adaptKey(key);
        addToCache(adaptedKey, value, expiration);
        keyMaster.addKey(key, adaptedKey);
    }
    @Override
    public void putRaw(String key, Object value){
        putRaw(key, value, 0);
    }
    @Override
    public void putRaw(String key, Object value, int expiration){

        addToCache(key, value, expiration);
        keyMaster.addKey(key, key);
    }

    private void addToCache(String adaptedKey, Object value, int expiration) {
        if(value ==null ){
            return;
        }

        evictableCache.putWithWriter(new Element(adaptedKey, value, expiration <= 0, expiration, expiration));

    }

    private static boolean isEvictable(Object o){
        return true;
        /*
        if(o ==null){
            //TODO should we throw exception?
            return true;
        }
        CacheStrategy cacheStrat=o.getClass().getAnnotation(CacheStrategy.class);

        if(cacheStrat ==null){
            return true;
        }
        return cacheStrat.evictable();
        */
    }

    @Override
    public boolean contains(String key){
        String adaptedKey = keyMaster.adaptKey(key);
        Element e = evictableCache.get(adaptedKey);

        return e !=null;
    }

    @Override
    public void put(String key, Object value) {
        put(key, value, 0);
    }

    @Override
    public <T> T getOrElseRaw(String key, TypedCallable<T> generator) throws Exception {
        return getOrElseRaw(key, generator, 0);
    }

    @Override
    public <T> T getOrElse(String key, TypedCallable<T> generator) throws Exception {
        return getOrElse(key, generator, 0);
    }

    @Override
    public void close() {
        if(isClosed){
            return;
        }
        isClosed=true;
        disposeCache(evictableCache);
    }

    private void disposeCache(Ehcache c){
        try {
            //shouldn't call dispose, the CacheManager will do that for us
            CacheManager.getInstance().removeCache(c.getName());
        }catch(Exception e){
            log.trace("Disposing cache " + c.getName(), e);
        }
    }

}
