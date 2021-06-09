package gsrs;

import java.util.Set;

public interface GsrsManualDirtyMaker {

    void setIsDirty(String dirtyField);
    Set<String> getDirtyFields();
    void clearDirtyFields();
    boolean isDirty(String field);

    /**
     * Only invoke the given action if the field is not yet dirty.
     * @param field
     * @param action the action to invoke; can not be null.
     * @throws NullPointerException if any parameter is null.
     */
    void performIfNotDirty(String field, Runnable action);

}
