package jd.ide.intellij;

import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static jd.ide.intellij.JavaDecompilerRefreshSupportService.JavaDecompilerRefreshListener;

/**
 * Caching decorator for the decompilation service.
 *
 * <p>
 *     Note this service is used by different part of intellij, like quick definition, editor, structural indexation. etc.
 * </p>
 *
 * <p>
 *     As these services need to use the same decompiled text, the decompilation is cached in this class. Aside of
 *     that the decompiled text can change in regard of the activated options of the plugin. For this reason
 *     the cache need to listen to refresh requests.
 * </p>
 *
 * @see #decompile(VirtualFile)
 */
public class CachingJavaDecompilerService {
    private static Logger LOGGER = Logger.getInstance(CachingJavaDecompilerService.class);

    private final JavaDecompilerService javaDecompilerService;
    private final LoadingCache<DecompiledFileKey, CharSequence> decompiledCache;


    public CachingJavaDecompilerService() {
        javaDecompilerService = new JavaDecompilerService();
        decompiledCache = makeDecompiledCache();
        ServiceManager.getService(JavaDecompilerRefreshSupportService.class)
                .registerRefreshListener(new JavaDecompilerRefreshListener() { public void onRefreshDecompiledFiles() {
                        decompiledCache.invalidateAll();
                    }
                });
    }


    @NotNull
    public CharSequence decompile(VirtualFile virtualFile) throws RuntimeException{
        return accessToDecompiledText(virtualFile);
    }

    public String getVersion() {
        return javaDecompilerService.getVersion();
    }

    private CharSequence accessToDecompiledText(VirtualFile virtualFile) {
        try {
            CharSequence charSequence = decompiledCache.get(new DecompiledFileKey(virtualFile));

            LOGGER.warn("[JD] decompiling : '" + virtualFile.getPresentableName() + "' length = " + charSequence.length());
            return charSequence;
        } catch (ExecutionException e) {
            Throwables.propagate(e);
            return null;
        }
    }


    private LoadingCache<DecompiledFileKey, CharSequence> makeDecompiledCache() {
        return CacheBuilder.newBuilder()
//                .concurrencyLevel(4)
                .expireAfterAccess(20, TimeUnit.MINUTES)
                .build(new CacheLoader<DecompiledFileKey, CharSequence>() {
                    @Override
                    public CharSequence load(DecompiledFileKey decompiledFileKey) throws Exception {
                        ServiceManager.getService(JavaDecompilerRefreshSupportService.class).markDecompiled(decompiledFileKey.virtualFile);
                        return javaDecompilerService.decompile(decompiledFileKey.virtualFile);
                    }
                });
    }


    private static class DecompiledFileKey {
        VirtualFile virtualFile;

        private DecompiledFileKey(VirtualFile virtualFile) {
            this.virtualFile = virtualFile;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(virtualFile);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final DecompiledFileKey other = (DecompiledFileKey) obj;
            return Objects.equal(this.virtualFile, other.virtualFile);
        }
    }
}
