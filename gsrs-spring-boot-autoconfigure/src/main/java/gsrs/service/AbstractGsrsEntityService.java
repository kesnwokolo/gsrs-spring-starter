package gsrs.service;

import com.fasterxml.jackson.databind.JsonNode;
import gov.nih.ncats.common.util.CachedSupplier;
import gsrs.EntityPersistAdapter;
import gsrs.controller.IdHelper;
import gsrs.repository.EditRepository;
import gsrs.validator.DefaultValidatorConfig;
import gsrs.validator.GsrsValidatorFactory;
import ix.core.controllers.EntityFactory;
import ix.core.models.Edit;
import ix.core.util.EntityUtils;
import ix.core.util.LogUtil;
import ix.core.validator.ValidationMessage;
import ix.core.validator.ValidationResponse;
import ix.core.validator.Validator;
import ix.core.validator.ValidatorCallback;
import ix.ginas.utils.validation.ValidatorFactory;
import ix.utils.pojopatch.PojoDiff;
import ix.utils.pojopatch.PojoPatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract implementation of {@link GsrsEntityService} to reduce most
 * of the boilerplate of creating a {@link GsrsEntityService}.
 * @param <T> the entity type.
 * @param <I> the type for the entity's ID.
 */
@Slf4j
public abstract class AbstractGsrsEntityService<T,I> implements GsrsEntityService<T, I> {

    @Autowired
    private GsrsValidatorFactory validatorFactoryService;

    @Autowired
    private EditRepository editRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private EntityPersistAdapter entityPersistAdapter;

    private final String context;
    private final Pattern idPattern;

    private CachedSupplier<ValidatorFactory> validatorFactory;
    /**
     * Create a new GSRS Entity Service with the given context.
     * @param context the context for the routes of this controller.
     * @param idPattern the {@link Pattern} to match an ID.  This will be used to determine
     *                  Strings that are IDs versus Strings that should be flex looked up.  @see {@link #flexLookup(String)}.
     * @throws NullPointerException if any parameters are null.
     */
    public AbstractGsrsEntityService(String context, Pattern idPattern) {
        this.context = Objects.requireNonNull(context, "context can not be null");
        this.idPattern = Objects.requireNonNull(idPattern, "ID pattern can not be null");
    }
    /**
    * Create a new GSRS Entity Service with the given context.
    * @param context the context for the routes of this controller.
    * @param idHelper the {@link IdHelper} to match an ID.  This will be used to determine
    *                  Strings that are IDs versus Strings that should be flex looked up.  @see {@link #flexLookup(String)}.
    * @throws NullPointerException if any parameters are null.
    */
    public AbstractGsrsEntityService(String context, IdHelper idHelper) {
        this(context, Pattern.compile("^"+idHelper.getRegexAsString() +"$"));
    }

    @Override
    public String getContext() {
        return context;
    }

    @PostConstruct
    private void initValidator(){
        //need this in a post construct so the validator factory service is injected
        //This is added to the initization Group so that we can reset this in tests

        //This cache might be unncessary as of now the call to newFactory isn't cached
        //by tests where the return value could change over time?  but keep it here anyway for now...
        validatorFactory = ENTITY_SERVICE_INTIALIZATION_GROUP.add(()->validatorFactoryService.newFactory(context));
    }

    /**
     * Create a new instance of your entity type from the provided JSON.
     * Perform any object clean up or "data cleansing" here.
     *
     * @param json the JSON to use; will never be null.
     *
     * @return a new entity instance will never be null.
     *
     * @throws IOException if there's a problem processing the JSON.
     */
    protected abstract T fromNewJson(JsonNode json) throws IOException;
    /**
     * Create List of  new instances of your entity type from the provided JSON which
     * is a List of entities.
     * Perform any object clean up or "data cleansing" here.
     *
     * @param list the JSON to use; will never be null.
     *
     * @return a new List of instances will never be null and probably shouldn't be empty.
     *
     * @throws IOException if there's a problem processing the JSON.
     */
    protected abstract List<T> fromNewJsonList(JsonNode list) throws IOException;
    /**
     * Create a instance of your entity type from the provided JSON representing
     * an updated version of a  record.
     * Perform any object clean up or "data cleansing" here but keep in mind different data cleansings
     * might be done on new vs updated records.
     *
     * @param json the JSON to use; will never be null.
     *
     * @return a new entity instance will never be null.
     *
     * @throws IOException if there's a problem processing the JSON.
     */
    protected abstract T fromUpdatedJson(JsonNode json) throws IOException;



    /**
     * Create List of  instances of your entity type from the provided JSON which
     * is a List of updated versions of records.
     * Perform any object clean up or "data cleansing" here  here but keep in mind different data cleansings
     * might be done on new vs updated records.
     *
     * @param list the JSON to use; will never be null.
     *
     * @return a new List of instances will never be null and probably shouldn't be empty.
     *
     * @throws IOException if there's a problem processing the JSON.
     */
    protected abstract List<T> fromUpdatedJsonList(JsonNode list) throws IOException;

    /**
     * Create a JSON representation of your entity.
     * @param t
     * @return
     * @throws IOException
     */
    protected abstract JsonNode toJson(T t) throws IOException;

    /**
     * Save the given entity to your data repository.
     * @param t the entity to save.
     * @return the new saved entity, usually this is the same object as the t passed in but it doesn't have to be.
     * The returned object may have different fields set like id, or audit information etc.
     */
    protected abstract T create(T t);

    /**
     * Get the entity from your data repository with the given id.
     * @param id the id to use to look up the entity.
     * @return an Optional that is empty if there is no entity with the given id;
     * or an Optional that has the found entity.
     */
    public abstract Optional<T> get(I id);

    /**
     * Parse the Id from the given String.
     * @param idAsString the String to parse into an ID.
     * @return the ID as the correct type.
     */
    public abstract I parseIdFromString(String idAsString);


    /**
     * Get the ID from the entity.
     * @param entity the entity to get the id of.
     * @return the ID of this entity.
     */
    //TODO should this be turned into a Function so we can pass a method reference in constructor?
    public abstract I getIdFrom(T entity);

    /**
     * Update the given entity in the repository.
     * @param t the entity to update.
     * @return the new saved entity, usually this is the same object as the t passed in but it doesn't have to be.
     * The returned object may have different fields set like  audit information etc.
     */
    protected abstract T update(T t);


    @Override
    @Transactional
    public  CreationResult<T> createEntity(JsonNode newEntityJson) throws IOException {
        T newEntity = fromNewJson(newEntityJson);

        Validator<T> validator  = validatorFactory.getSync().createValidatorFor(newEntity, null, DefaultValidatorConfig.METHOD_TYPE.CREATE);

        ValidationResponse<T> response = createValidationResponse(newEntity, null, DefaultValidatorConfig.METHOD_TYPE.CREATE);
        ValidatorCallback callback = createCallbackFor(newEntity, response, DefaultValidatorConfig.METHOD_TYPE.CREATE);
        validator.validate(newEntity, null, callback);
        callback.complete();

        CreationResult.CreationResultBuilder<T> builder = CreationResult.<T>builder()
                                                                            .validationResponse(response);

        if(response!=null && !response.isValid()){

            return builder.build();
        }
        return builder.createdEntity(transactionalPersist(newEntity))
                                .created(true)
                                .build();

    }

    protected <T>  ValidationResponse<T> createValidationResponse(T newEntity, Object oldEntity, DefaultValidatorConfig.METHOD_TYPE type){
        return new ValidationResponse<T>(newEntity);
    }

    /**
     * Persist the given entity inside a transaction.
     * This method should NOT be overridden by client code
     * and is only here so Spring AOP can subclass it to make it transactional.
     * @param newEntity the entity to persist.
     * @return the persisted entity.
     * @implSpec delegates to {@link #create(Object)} inside a {@link Transactional}.
     */
    @Transactional
    protected T transactionalPersist(T newEntity){
        T saved = create(newEntity);

        return saved;
    }

    @Override
    @Transactional
    public UpdateResult<T> updateEntity(JsonNode updatedEntityJson) throws Exception {
        T updatedEntity = fromUpdatedJson(updatedEntityJson);
        //updatedEntity should have the same id
        I id = getIdFrom(updatedEntity);
        Optional<T> opt = get(id);
        if(!opt.isPresent()){
            return UpdateResult.<T>builder().status(UpdateResult.STATUS.NOT_FOUND).build();
        }

        T oldEntity = opt.get();
        String oldJson = EntityFactory.EntityMapper.FULL_ENTITY_MAPPER().toJson(oldEntity);
        Validator<T> validator  = validatorFactory.getSync().createValidatorFor(updatedEntity, oldEntity, DefaultValidatorConfig.METHOD_TYPE.UPDATE);

        ValidationResponse<T> response = createValidationResponse(updatedEntity, oldEntity, DefaultValidatorConfig.METHOD_TYPE.UPDATE);
        ValidatorCallback callback = createCallbackFor(updatedEntity, response, DefaultValidatorConfig.METHOD_TYPE.UPDATE);
        validator.validate(updatedEntity, oldEntity, callback);

        callback.complete();
        UpdateResult.UpdateResultBuilder<T> builder = UpdateResult.<T>builder()
                                                            .validationResponse(response)
                                                            .oldJson(oldJson);

        if(response!=null && !response.isValid()){
            return builder.status(UpdateResult.STATUS.ERROR)
                    .build();
        }

        PojoPatch<T> patch = PojoDiff.getDiff(oldEntity, updatedEntity);
        System.out.println("changes = " + patch.getChanges());
        final List<Object> removed = new ArrayList<Object>();

        //Apply the changes, grabbing every change along the way
        Stack changeStack=patch.apply(oldEntity, c->{
            if("remove".equals(c.getOp())){
                removed.add(c.getOldValue());
            }
            LogUtil.trace(()->c.getOp() + "\t" + c.getOldValue() + "\t" + c.getNewValue());
        });
        if(changeStack.isEmpty()){
            throw new IllegalStateException("No change detected");
        }else{
            LogUtil.debug(()->"Found:" + changeStack.size() + " changes");
        }
        //This is the last line of defense for making sure that the patch worked
        //Should throw an exception here if there's a major problem
        String serialized= EntityUtils.EntityWrapper.of(oldEntity).toJsonDiffJson();

        while(!changeStack.isEmpty()){
            Object v=changeStack.pop();
            EntityUtils.EntityWrapper ewchanged= EntityUtils.EntityWrapper.of(v);
            if(!ewchanged.isIgnoredModel() && ewchanged.isEntity()){

                entityManager.merge(ewchanged.getValue());
            }
        }

        //explicitly delete deleted things
        //This should ONLY delete objects which "belong"
        //to something. That is, have a @SingleParent annotation
        //inside

        removed.stream()
                .filter(Objects::nonNull)
                .map(o-> EntityUtils.EntityWrapper.of(o))
                .filter(ew->ew.isExplicitDeletable())
                .forEach(ew->{
                    log.warn("deleting:" + ew.getValue());
                    entityManager.remove(ew.getValue());
                });

        System.out.println("updated entity = " + oldEntity);
        try {
            builder.updatedEntity(transactionalUpdate(oldEntity, oldJson));
            builder.status(UpdateResult.STATUS.UPDATED);
        }catch(Throwable t){
            t.printStackTrace();
            builder.status(UpdateResult.STATUS.ERROR);
        }
        //match 200 status of old GSRS
        return builder.build();
    }

    protected <T> ValidatorCallback createCallbackFor(T object, ValidationResponse<T> response, DefaultValidatorConfig.METHOD_TYPE type) {
        return new ValidatorCallback() {
            @Override
            public void addMessage(ValidationMessage message) {
                response.addValidationMessage(message);
            }

            @Override
            public void setInvalid() {
                response.setValid(false);
            }

            @Override
            public void setValid() {
                response.setValid(true);
            }

            @Override
            public void haltProcessing() {

            }

            @Override
            public void addMessage(ValidationMessage message, Runnable appyAction) {
                response.addValidationMessage(message);
                appyAction.run();
            }

            @Override
            public void complete() {
                if(response.hasError()){
                    response.setValid(false);
                }
            }
        };
    }

    /**
     * Update the given entity inside a transaction.
     * This method should NOT be overridden by client code
     * and is only here so Spring AOP can subclass it to make it transactional.
     * @param entity the entity to update.
     * @return the persisted entity.
     * @implSpec delegates to {@link #update(Object)} inside a {@link Transactional}.
     */
    @Transactional
    protected T transactionalUpdate(T entity, String oldJson){

        T after =  update(entity);
        EntityUtils.EntityWrapper<?> ew = EntityUtils.EntityWrapper.of(after);
        Edit edit = new Edit(ew.getEntityClass(), ew.getKey().getIdString());

        edit.oldValue = oldJson;
        String newVersionStr = ew.getVersion().orElse(null);
        if(newVersionStr ==null) {
            edit.version = null;
        }else{
            edit.version = Long.toString(Long.parseLong(newVersionStr) -1);
        }
        edit.comments = ew.getChangeReason().orElse(null);
        edit.kind = ew.getKind();
        edit.newValue = ew.toFullJson();

        editRepository.save(edit);
        System.out.println("edit = " + edit.id);
        return after;
    }

    @Override
    public ValidationResponse<T> validateEntity(JsonNode updatedEntityJson) throws Exception {
        T updatedEntity = fromUpdatedJson(updatedEntityJson);
        //updatedEntity should have the same id
        I id = getIdFrom(updatedEntity);

        Optional<T> opt = id==null? Optional.empty() : get(id);
        Validator<T> validator;
        ValidationResponse<T> response;
        ValidatorCallback callback;
        if(opt.isPresent()){
            validator  = validatorFactory.getSync().createValidatorFor(updatedEntity, opt.get(), DefaultValidatorConfig.METHOD_TYPE.UPDATE);
            response = createValidationResponse(updatedEntity, opt.orElse(null), DefaultValidatorConfig.METHOD_TYPE.UPDATE);
            callback = createCallbackFor(updatedEntity, response, DefaultValidatorConfig.METHOD_TYPE.UPDATE);
            validator.validate(updatedEntity, opt.orElse(null), callback);

        }else{
            validator  = validatorFactory.getSync().createValidatorFor(updatedEntity, null, DefaultValidatorConfig.METHOD_TYPE.CREATE);
            response = createValidationResponse(updatedEntity, opt.orElse(null), DefaultValidatorConfig.METHOD_TYPE.CREATE);
            callback = createCallbackFor(updatedEntity, response, DefaultValidatorConfig.METHOD_TYPE.CREATE);
            validator.validate(updatedEntity, opt.orElse(null), callback);
        }
        callback.complete();

        //always send 200 even if validation has errors?
        return response;

    }

    private boolean isId(String s){
        Matcher idMatch = idPattern.matcher(s);
        return idMatch.matches();
    }

    @Override
    public Optional<T> getEntityBySomeIdentifier(String id) {
        Optional<T> opt;
        if(isId(id)) {
            opt = get(parseIdFromString(id));
        }else {
            opt = flexLookup(id);
        }
        return opt;
    }
    @Override
    public Optional<I> getEntityIdOnlyBySomeIdentifier(String id) {
        Optional<I> opt;
        if(isId(id)) {
            opt = Optional.ofNullable(parseIdFromString(id));
        }else {
            opt = flexLookupIdOnly(id);
        }
        return opt;
    }

    /**
     * Fetch an entity's real ID from the data repository using a unique
     * identifier that isn't the entity's ID.  Override this method
     * if a more efficient query can be made rather than fetching the whole record.
     *
     * @implSpec the default implementation of this method is:
     * <pre>
     * {@code
     *  Optional<T> opt = flexLookup(someKindOfId);
     *  if(!opt.isPresent()){
     *     return Optional.empty();
     *  }
     *  return Optional.of(getIdFrom(opt.get()));
     * }
     * </pre>
     * @param someKindOfId a String that isn't the ID of the entity.
     * @return an Optional that is empty if there is no entity with the given id;
     *  or an Optional that has the found entity's ID.
     */
    protected Optional<I> flexLookupIdOnly(String someKindOfId){
        Optional<T> opt = flexLookup(someKindOfId);
        if(!opt.isPresent()){
            return Optional.empty();
        }
        return Optional.of(getIdFrom(opt.get()));
    }

    /**
     * Fetch an entity from the data repository using a unique
     * identifier that isn't the entity's ID.
     *
     * @param someKindOfId a String that isn't the ID of the entity.
     * @return an Optional that is empty if there is no entity with the given id;
     *  or an Optional that has the found entity.
     */
    public abstract Optional<T> flexLookup(String someKindOfId);
}
