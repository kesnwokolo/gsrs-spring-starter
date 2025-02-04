package gsrs.autoconfigure;

import gov.nih.ncats.common.util.CachedSupplier;
import gsrs.springUtils.AutowireHelper;
import ix.ginas.exporters.ExporterFactory;
import ix.ginas.exporters.OutputFormat;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import java.io.File;
import java.util.*;

@Component
@Configuration
@ConfigurationProperties("ix.ginas.export")
@Data
public class GsrsExportConfiguration {

    private File path = new File("./exports");
    private File tmpDir=null;

    private Map<String, List<Class>> factories;

    private Map<String, Map<String, OutputFormat>> extensionMap = new LinkedHashMap<>();

    private Map<String, List<ExporterFactory>> exporters = new HashMap<>();

    CachedSupplier initializer = CachedSupplier.ofInitializer( ()->{
        AutowireHelper.getInstance().autowire(GsrsExportConfiguration.this);
        if(factories ==null){
            return;
        }
        for(Map.Entry<String, List<Class>> entry : factories.entrySet()){
            String context = entry.getKey();
            List<ExporterFactory> list = new ArrayList<>();
            for(Class c : entry.getValue()){
                try {
                    ExporterFactory factory = (ExporterFactory)c.newInstance();

                    list.add(AutowireHelper.getInstance().autowireAndProxy(factory));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            exporters.put(context, list);
        }
    });

    /**
     * Get all the Supported {@link OutputFormat}s
     * by this plugin.
     * @return a Set of {@link OutputFormat}s, may be an
     * empty set if no exporters are found.
     */
    public Set<OutputFormat> getAllSupportedFormats(String context){

        initializer.get();

        //This mess with reverse iterating and then reversing again
        //is because we want the exporters listed first in the config file
        //to have priority over later listed exporters.  So if an earlier
        //exporter has the same extension as a later exporter, the earlier one should
        //have priority.
        //
        //So we go through the list backwards so earlier exporter's extensions
        //overwrite later exporters
        //
        //But then we have to reverse the list again so
        //the final display order matches the input list order.


        List<OutputFormat> list = getAllOutputsAsList(exporters.get(context));
        //go in reverse order to prefer the factories listed first
        ListIterator<OutputFormat> iterator = list.listIterator(list.size());
        Set<OutputFormat> set = new LinkedHashSet<>();
        while(iterator.hasPrevious()){
            set.add(iterator.previous());
        }
        //reverse it again
        List<OutputFormat> resortList = new ArrayList<>(set.size());

        for(OutputFormat f : set){
            resortList.add(f);
        }
        Collections.reverse(resortList);


        return new LinkedHashSet<>(resortList);
    }

    private List<OutputFormat> getAllOutputsAsList(List<ExporterFactory> exporters) {

        List<OutputFormat> list = new ArrayList<>();
        if(exporters !=null) {
            for (ExporterFactory factory : exporters) {
                list.addAll(factory.getSupportedFormats());
            }
        }
        return list;
    }

    /**
     * Get the {@link ExporterFactory} for the given {@link ix.ginas.exporters.ExporterFactory.Parameters}.
     * @param params the parameters to use to find the Exporter.
     * @return a {@link ExporterFactory} or {@code null} if no exporter is found that supports
     * those parameter configurations.
     *
     * @throws NullPointerException if params is null.
     */
    public ExporterFactory getExporterFor(String context, ExporterFactory.Parameters params){
        Objects.requireNonNull(params);
        initializer.get();
        List<ExporterFactory> contextExporters = exporters.get(context);
        if(contextExporters !=null) {
            for (ExporterFactory factory : contextExporters) {
                if (factory.supports(params)) {
                    return factory;
                }
            }
        }
        return null;
    }

}
