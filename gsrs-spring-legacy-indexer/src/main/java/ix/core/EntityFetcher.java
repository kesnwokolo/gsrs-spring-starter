package ix.core;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import gov.nih.ncats.common.util.TimeUtil;
import gsrs.cache.GsrsCache;
import gsrs.repository.BackupRepository;
import gsrs.springUtils.StaticContextAccessor;
import ix.core.models.BackupEntity;
import ix.core.search.LazyList.NamedCallable;
import ix.core.util.EntityUtils;
import ix.core.util.EntityUtils.Key;
import ix.utils.CallableUtil.TypedCallable;
import lombok.extern.slf4j.Slf4j;


/**
 * 
 * Utility "wrapper" for producing an entity from some source.
 * 
 * Currently, it accepts a Key, and will generate a value on 
 * call, by various sources, depending on the CacheType.
 * 
 * @author peryeata
 *
 * @param <T>
 */
@Slf4j
public class EntityFetcher<T> implements NamedCallable<Key,T>{
    private static Object getOrFetchRecordIfNotDirty(Key k) throws Exception {
        GsrsCache ixcache = getIxCache();
        return ixcache.getOrElseRawIfDirty(k.toString(), ()->{
            Optional<EntityUtils.EntityWrapper<?>> ret = k.fetchReadOnlyFull();
            if(ret.isPresent()){
                return ret.get().getValue();
            }
            return null;
        });
    }
    
    private static Object getOrFetchRecordPerUserIfNotDirty(Key k) throws Exception {
        GsrsCache ixcache = getIxCache();
        return ixcache.getOrElseIfDirty(k.toString(), ()->{
            Optional<EntityUtils.EntityWrapper<?>> ret = k.fetchReadOnlyFull();
            if(ret.isPresent()){
                return ret.get().getValue();
            }
            return null;
        });
    }
    
    private static GsrsCache getIxCache() {
        GsrsCache cache = StaticContextAccessor.getBean(GsrsCache.class);
        return cache;
    }
    
    private static BackupRepository getBackupRepository() {
        BackupRepository repo = StaticContextAccessor.getBean(BackupRepository.class);
        return repo;
    }
    
	public enum CacheType{
		/**
		 * Don't use a cache always refetch from db.
		 */
		NO_CACHE{
			@Override
			<T> T get(EntityFetcher<T> fetcher) throws Exception {
				return (T) fetcher.findObject();
			}
		},
		/**
		 * Everyone sees everything (works)
		 */
		GLOBAL_CACHE{
			@Override
			<T> T get(EntityFetcher<T> fetcher) throws Exception{
				return (T) getOrFetchRecordIfNotDirty(fetcher.theKey);
			}
		},

        /**
         * OLD way (user-specific) (WARNING: BROKEN?)
         */
        DEFAULT_CACHE{
            @Override
            <T> T get(EntityFetcher<T> fetcher) throws Exception {
                return (T) getOrFetchRecordPerUserIfNotDirty(fetcher.theKey);
            }
        },
		/**
		 * Store object here, return it directly.
		 */
		ACTIVE_LOAD{
			@Override
			<T> T get(EntityFetcher<T> fetcher) throws Exception {
				return fetcher.getOrReload().get();
			}
		},
		BACKUP_JSON_CACHE {
			@Override
			<T> T get(EntityFetcher<T> fetcher) throws Exception {	
			    
				if(fetcher.theKey.getEntityInfo().hasBackup()){
				    GsrsCache ixCache=getIxCache();
				    String jkey = fetcher.theKey.toString() +"_JSON";
				    Supplier<T> ifNot=()->{
				        try {
                            return GLOBAL_CACHE.get(fetcher);
                        }catch(Exception e) {
                            return null;
                        }  
				    };
                    TypedCallable<T> caller = ()->{
                        TransactionTemplate ttemp = fetcher.theKey.getTransactionTemplate();
                        ttemp.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        ttemp.setReadOnly(true);
                        try {
                            T tret= ttemp.execute(s->{
                                BackupEntity be = getBackupRepository().getByEntityKey(fetcher.theKey).orElse(null);
                                if(be==null){
                                    return ifNot.get();
                                }else{
                                    try {
                                        T ret=(T)be.getInstantiated();
                                        return ret;
                                    }catch(Exception e){
                                        log.error("Trouble deserializing entity JSON", e);
                                        return ifNot.get();
                                    }
                                }
                            });
                            return tret;
                        }catch(Exception e) {
                            log.error("Error fetching record in transaction:" + fetcher.theKey.toString(), e);
                            return ifNot.get();
                        }
                       
                    };
                    try{
                        return ixCache.getOrElseRawIfDirty(jkey, caller);
                    }catch(Exception e){
                        return ifNot.get();
                    }
				}
				return GLOBAL_CACHE.get(fetcher);
			}
		};


		 abstract <T> T get(EntityFetcher<T> fetcher) throws Exception;

	}
	public final CacheType cacheType; 
	
	final Key theKey;
	
	private Optional<T> stored = Optional.empty(); //
	
	long lastFetched=0l;
	
	public EntityFetcher(Key theKey){
		this(theKey, CacheType.BACKUP_JSON_CACHE); // This option caches based on
		                                           // raw JSON. This turns out to
		                                           // work pretty well, if not perfectly.
	}
	
	public EntityFetcher(Key theKey, CacheType ct) {
        Objects.requireNonNull(theKey);
        cacheType= ct;
        this.theKey=theKey.toRootKey();
    }
	
	@Override
	public T call() throws Exception {
		return cacheType.get(this);
	}
	
	public Key getName(){
		return theKey;
	}
	
	public Optional<T> getOrReload(){
		if(stored.isPresent()){
			return stored;
		}else{
			return reload();
		}
	}
	
	public Optional<T> getIfPossible(){
	    try{
	        return Optional.ofNullable(this.call());
	    }catch(Exception e) {
	        return Optional.empty();
	    }
	}
	
	
	//Refresh the "localest" of caches
	public Optional<T> reload() throws NoSuchElementException {
		try{
			stored=Optional.of(findObject());
		}catch(Exception e){
			stored=Optional.empty();
		}
		return stored;
	}
	
	@SuppressWarnings("unchecked")
    public T findObject () throws NoSuchElementException {
	    lastFetched=TimeUtil.getCurrentTimeMillis();
		return (T) theKey.fetchReadOnlyFull()
		        .get()
		        .getValue();
    }

	public static EntityFetcher<?> of(Key k){
		return new EntityFetcher<>(k);
	}
	
	public static <T> EntityFetcher<T> of(Key k, Class<T> cls) {
		return new EntityFetcher<>(k);
	}
	
	
	public static EntityFetcher<?> of(Key k, CacheType cacheType) {
        return new EntityFetcher<>(k, cacheType);
    }
    
    public static <T> EntityFetcher<T> of(Key k, CacheType cacheType,Class<T> cls) {
        return new EntityFetcher<>(k, cacheType);
    }
    
}
