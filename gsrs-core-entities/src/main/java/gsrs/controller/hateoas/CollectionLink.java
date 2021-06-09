package gsrs.controller.hateoas;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.hateoas.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
@Data
@AllArgsConstructor
public class CollectionLink extends Link {
    private int count;
    @JsonIgnore
    //this is jsonIgnore because we delegate the getters with the same json annotations so ignoring means we don't list everything twice
    private Link link;


    CollectionLink(int count, String field, Link link){
        this.count = count;
        this.link = link;
    }

    @Override
    public List<Affordance> getAffordances() {
        return link.getAffordances();
    }

    @Override
    public Link withSelfRel() {
        return link.withSelfRel();
    }

    @Override
    public Link andAffordance(Affordance affordance) {
        return link.andAffordance(affordance);
    }

    @Override
    public Link andAffordances(List<Affordance> affordances) {
        return link.andAffordances(affordances);
    }

    @Override
    public Link withAffordances(List<Affordance> affordances) {
        return link.withAffordances(affordances);
    }

    @Override
    @JsonIgnore
    public List<String> getVariableNames() {
        return link.getVariableNames();
    }

    @Override
    @JsonIgnore
    public List<TemplateVariable> getVariables() {
        return link.getVariables();
    }

    @Override
    public boolean isTemplated() {
        return link.isTemplated();
    }

    @Override
    public Link expand(Object... arguments) {
        return link.expand(arguments);
    }

    @Override
    public Link expand(Map<String, ?> arguments) {
        return link.expand(arguments);
    }

    @Override
    public Link withRel(LinkRelation relation) {
        return link.withRel(relation);
    }

    @Override
    public Link withRel(String relation) {
        return link.withRel(relation);
    }

    @Override
    public boolean hasRel(String rel) {
        return link.hasRel(rel);
    }

    @Override
    public boolean hasRel(LinkRelation rel) {
        return link.hasRel(rel);
    }

    @Override
    public URI toUri() {
        return link.toUri();
    }

    public static Link valueOf(String element) {
        return Link.valueOf(element);
    }

    @Override
    public Link withHref(String href) {
        return link.withHref(href);
    }

    @Override
    public Link withHreflang(String hreflang) {
        return link.withHreflang(hreflang);
    }

    @Override
    public Link withMedia(String media) {
        return link.withMedia(media);
    }

    @Override
    public Link withTitle(String title) {
        return link.withTitle(title);
    }

    @Override
    public Link withType(String type) {
        return link.withType(type);
    }

    @Override
    public Link withDeprecation(String deprecation) {
        return link.withDeprecation(deprecation);
    }

    @Override
    public Link withProfile(String profile) {
        return link.withProfile(profile);
    }

    @Override
    public Link withName(String name) {
        return link.withName(name);
    }

    @Override
    @JsonProperty
    public LinkRelation getRel() {
        return link.getRel();
    }

    @Override
    @JsonProperty
    public String getHref() {
        return link.getHref();
    }

    @Override
    @JsonProperty
    public String getHreflang() {
        return link.getHreflang();
    }

    @Override
    @JsonProperty
    public String getMedia() {
        return link.getMedia();
    }

    @Override
    @JsonProperty
    public String getTitle() {
        return link.getTitle();
    }

    @Override
    @JsonProperty
    public String getType() {
        return link.getType();
    }

    @Override
    @JsonProperty
    public String getDeprecation() {
        return link.getDeprecation();
    }

    @Override
    @JsonProperty
    public String getProfile() {
        return link.getProfile();
    }

    @Override
    @JsonProperty
    public String getName() {
        return link.getName();
    }

    @Override
    @JsonProperty
    public UriTemplate getTemplate() {
        return link.getTemplate();
    }


}
