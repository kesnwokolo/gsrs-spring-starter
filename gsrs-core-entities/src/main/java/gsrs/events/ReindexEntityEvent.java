package gsrs.events;

import ix.core.util.EntityUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class ReindexEntityEvent implements ReindexEvent {

    private UUID reindexId;
    private EntityUtils.Key entityKey;
}
