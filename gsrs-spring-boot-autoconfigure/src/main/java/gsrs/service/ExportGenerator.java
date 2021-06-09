package gsrs.service;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.stream.Stream;

public interface ExportGenerator<I,T> {

    Supplier<Stream<T>> generateExportFrom(String context, I input) throws IOException;
}
