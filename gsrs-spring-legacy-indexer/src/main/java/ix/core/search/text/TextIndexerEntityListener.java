package ix.core.search.text;

import gsrs.DefaultDataSourceConfig;
import gsrs.events.MaintenanceModeEvent;
import gsrs.events.ReindexEntityEvent;
import gsrs.indexer.IndexCreateEntityEvent;
import gsrs.indexer.IndexRemoveEntityEvent;
import gsrs.indexer.IndexUpdateEntityEvent;
import gsrs.springUtils.AutowireHelper;
import gsrs.springUtils.StaticContextAccessor;
import ix.core.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Hibernate Entity listener that will update our legacy {@link TextIndexer}.
 */
@Component
@Transactional(readOnly = true)
public class TextIndexerEntityListener {

    @Autowired
    private TextIndexerFactory textIndexerFactory;

//    
//    @PersistenceContext(unitName =  DefaultDataSourceConfig.NAME_ENTITY_MANAGER)
//    private EntityManager em;

    private void autowireIfNeeded(){
        if(textIndexerFactory==null) {
            AutowireHelper.getInstance().autowire(this);
        }
    }
    @Async
    @TransactionalEventListener
    public void created(IndexCreateEntityEvent event) throws Exception{
        autowireIfNeeded();
        try {
            TextIndexer indexer = textIndexerFactory.getDefaultInstance();
            if(indexer !=null) {
                
                //refetch from db
                Optional<EntityUtils.EntityWrapper<?>> opt = event.getSource().fetch();
                if(opt.isPresent()) {
                    EntityUtils.EntityWrapper ew = opt.get();

                    if (event.shouldDeleteFirst()) {
                        indexer.remove(ew);
                    }
                    indexer.add(ew);
                }
            }
        } catch (Throwable e) {
            
            throw new Exception(e);
        }
    }
    @EventListener
    public void reindexEntity(ReindexEntityEvent event) throws IOException {
        autowireIfNeeded();
        Optional<EntityUtils.EntityWrapper<?>> opt = event.getOptionalFetchedEntityToReindex();
        
        if(opt.isPresent()){
            textIndexerFactory.getDefaultInstance().add(opt.get());
        }
    }
    @EventListener
    public void reindexing(MaintenanceModeEvent event) {
        autowireIfNeeded();
        //TODO: it shouldn't be the maintenance mode itself that triggers this
        // it should be a "WipeAllIndexes" event or something.
        if(event.getSource().isInMaintenanceMode()){
            textIndexerFactory.getDefaultInstance().newProcess();
        }else{
            textIndexerFactory.getDefaultInstance().doneProcess();
        }
    }

    @Async
    @TransactionalEventListener
    public void updateEntity(IndexUpdateEntityEvent event) {
        autowireIfNeeded();
        TextIndexer indexer = textIndexerFactory.getDefaultInstance();
        if(indexer !=null) {
            try {
                EntityUtils.EntityWrapper ew = event.getSource().fetch().get();
                indexer.update(ew);
            }catch(Throwable t){
                t.printStackTrace();
            }
        }
    }
    @Async
    @TransactionalEventListener
    public void deleteEntity(IndexRemoveEntityEvent event) throws Exception {
//        System.out.println("removing from index " + obj);
        autowireIfNeeded();
        textIndexerFactory.getDefaultInstance().remove(event.getSource());
    }
}
