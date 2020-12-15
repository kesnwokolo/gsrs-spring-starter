package gsrs;

import gsrs.controller.GsrsWebConfig;
import gsrs.entityProcessor.BasicEntityProcessorConfiguration;
import gsrs.entityProcessor.ConfigBasedEntityProcessorConfiguration;
import gsrs.indexer.ComponentScanIndexValueMakerConfiguration;
import gsrs.indexer.ComponentScanIndexValueMakerFactory;
import gsrs.validator.ConfigBasedValidatorFactoryConfiguration;
import ix.core.search.text.Lucene4IndexServiceFactory;
import ix.core.search.text.TextIndexerConfig;
import ix.core.search.text.TextIndexerFactory;
import ix.core.util.pojopointer.LambdaParseRegistry;
import ix.core.util.pojopointer.URIPojoPointerParser;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import java.util.ArrayList;
import java.util.List;

public class GsrsApiSelector implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata annotationMetadata) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(annotationMetadata.getAnnotationAttributes(EnableGsrsApi.class.getName(), false));
        EnableGsrsApi.IndexerType indexerType = attributes.getEnum("indexerType");

        List<Class> componentsToInclude = new ArrayList<>();
        componentsToInclude.add(GsrsWebConfig.class);
        switch(indexerType){
            case LEGACY: {
                componentsToInclude.add(TextIndexerFactory.class);
                componentsToInclude.add(TextIndexerConfig.class);
//                componentsToInclude.add(ComponentScanIndexValueMakerFactory.class);
                componentsToInclude.add(Lucene4IndexServiceFactory.class);

            }
        }
        EnableGsrsApi.IndexValueMakerDetector indexValueMakerDetector = attributes.getEnum("indexValueMakerDetector");
        switch (indexValueMakerDetector){
            case COMPONENT_SCAN:
                componentsToInclude.add(ComponentScanIndexValueMakerConfiguration.class);
                break;
            default: break;
        }

        EnableGsrsApi.EntityProcessorDetector entityProcessorDetector = attributes.getEnum("entityProcessorDetector");


        switch(entityProcessorDetector){
            case COMPONENT_SCAN:
                componentsToInclude.add(BasicEntityProcessorConfiguration.class);
                break;
            case CONF:
                componentsToInclude.add(ConfigBasedEntityProcessorConfiguration.class);
                break;
            default: break;
        }

        //TODO make something other than CONF based validator?
        componentsToInclude.add(ConfigBasedValidatorFactoryConfiguration.class);
        componentsToInclude.add(URIPojoPointerParser.class);
        componentsToInclude.add(LambdaParseRegistry.class);
        componentsToInclude.add(RegisteredFunctionProperties.class);

        return componentsToInclude.stream().map(Class::getName)
                .peek(c-> System.out.println(c)).toArray(i-> new String[i]);
    }
}
