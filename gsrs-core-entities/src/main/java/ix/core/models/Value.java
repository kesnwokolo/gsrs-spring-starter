package ix.core.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;


import javax.persistence.*;
import java.io.Serializable;

@Entity
@Table(name="ix_core_value")
@Inheritance
@DiscriminatorValue("VAL")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Value extends LongBaseModel implements Serializable{
    @Id
    @GeneratedValue(strategy=GenerationType.SEQUENCE, generator = "non-nullGen")
    @GenericGenerator(name = "non-nullGen", strategy = "ix.ginas.models.generators.NullLongGenerator")

    public Long id;
    public String label;
    
    public Value () {}
    public Value (String label) {
        this.label = label;
    }

    @JsonIgnore
    public Object getValue () {
        throw new UnsupportedOperationException
            ("getValue is not defined for class "+getClass().getName());
    }
}
