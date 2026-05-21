package dev.bluespice.testcommon;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class NgspiceExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(NgspiceExtension.class);
    private static final String PRESENT_KEY = "ngspice_present";

    @Override
    public void beforeAll(ExtensionContext context) {
        store(context).put(PRESENT_KEY, isNgspicePresent());
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (context.getTags().contains("intg")) {
            Boolean present = store(context).get(PRESENT_KEY, Boolean.class);
            assumeTrue(Boolean.TRUE.equals(present), "ngspice native library is not available");
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        store(context).remove(PRESENT_KEY);
    }

    private ExtensionContext.Store store(ExtensionContext context) {
        return context.getTestClass().isPresent()
                ? context.getRoot().getStore(NAMESPACE)
                : context.getStore(NAMESPACE);
    }

    private boolean isNgspicePresent() {
        String libraryFile = System.mapLibraryName("ngspice");
        return Arrays.stream(System.getProperty("java.library.path", "").split(System.getProperty("path.separator")))
                .map(Path::of)
                .map(path -> path.resolve(libraryFile))
                .anyMatch(Files::isRegularFile);
    }
}
