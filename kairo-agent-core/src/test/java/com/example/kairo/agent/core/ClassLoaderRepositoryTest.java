package com.example.kairo.agent.core;

import com.example.kairo.core.ClassLoaderIdentity;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ClassLoaderRepositoryTest {

    @Test
    void registersLoaderAndBuildsTree() {
        ClassLoaderRepository repo = new ClassLoaderRepository();
        ClassLoader parent = new TestLoader(null, "ParentLoader");
        ClassLoader child = new TestLoader(parent, "ChildLoader");

        String parentId = repo.register(parent);
        String childId = repo.register(child);

        assertThat(parentId).isNotBlank();
        assertThat(childId).isNotEqualTo(parentId);
        assertThat(repo.loaderInfo(parentId)).isPresent();
        assertThat(repo.loaderInfo(parentId).get().parentId())
                .isEqualTo(ClassLoaderIdentity.BOOTSTRAP);
        assertThat(repo.loaderInfo(childId).get().parentId()).isEqualTo(parentId);
        assertThat(repo.findLoader(childId)).contains(child);

        Map<String, List<LoaderInfo>> tree = repo.loaderTree();
        assertThat(tree.get(ClassLoaderIdentity.BOOTSTRAP)).isNotNull();
    }

    @Test
    void bootstrapLoaderIsTrackedSpecially() {
        ClassLoaderRepository repo = new ClassLoaderRepository();
        String id = repo.register((ClassLoader) null);
        assertThat(id).isEqualTo(ClassLoaderIdentity.BOOTSTRAP);
        assertThat(repo.loaderInfo(id)).isPresent();
        assertThat(repo.loaderInfo(id).get().className()).isEqualTo("bootstrap");
    }

    @Test
    void pollCollectedFiresListenersAndPurgesState() throws Exception {
        ClassLoaderRepository repo = new ClassLoaderRepository();
        AtomicInteger cleaned = new AtomicInteger();
        repo.addListener(id -> cleaned.incrementAndGet());

        WeakReference<ClassLoader> weak = registerCollectedLoader(repo);

        // Drop the strong reference held by the test and force collection.
        assertCollected(weak);
        int collected = repo.pollCollected();

        assertThat(collected).isEqualTo(1);
        assertThat(cleaned.get()).isEqualTo(1);
        // After cleanup the loader is no longer tracked.
        assertThat(repo.trackedLoaderCount()).isZero();
    }

    private static WeakReference<ClassLoader> registerCollectedLoader(ClassLoaderRepository repo) {
        ClassLoader loader = new TestLoader(null, "DoomedLoader");
        repo.register(loader);
        return new WeakReference<>(loader);
    }

    private static void assertCollected(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            System.gc();
            Thread.sleep(20L);
            if (ref.get() == null) {
                return;
            }
        }
        fail("ClassLoader was not garbage-collected; the repository is pinning it");
    }

    /** Minimal ClassLoader subclass so we can create distinct, collectable instances. */
    static final class TestLoader extends ClassLoader {
        TestLoader(ClassLoader parent, String name) {
            super(name, parent);
        }
    }
}
