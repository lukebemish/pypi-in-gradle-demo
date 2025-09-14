package dev.lukebemish.pypigradle;

import org.gradle.api.Action;
import org.gradle.api.artifacts.CacheableRule;
import org.gradle.api.artifacts.ComponentMetadataContext;
import org.gradle.api.artifacts.ComponentMetadataRule;
import org.gradle.api.artifacts.MutableVariantFilesMetadata;
import org.gradle.api.artifacts.VariantMetadata;
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.DocsType;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.model.ObjectFactory;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

import static dev.lukebemish.pypigradle.PyPIGradlePlugin.EXTRACT_EXTENSION_PREFIX;

@CacheableRule
public abstract class PyPIComponentRule implements ComponentMetadataRule {
    @Inject
    public PyPIComponentRule() {}
    
    @Inject
    protected abstract RepositoryResourceAccessor getResources();
    
    @Inject
    protected abstract ObjectFactory getObjects();

    public record TargetVariant(String operatingSystemFamily, String machineArchitecture) {
        public static final List<TargetVariant> ALL_VARIANTS;
        
        static {
            var all = new ArrayList<TargetVariant>();
            for (var os : new String[] {OperatingSystemFamily.LINUX, OperatingSystemFamily.MACOS, OperatingSystemFamily.WINDOWS}) {
                for (var arch : new String[] {MachineArchitecture.X86, MachineArchitecture.ARM64, MachineArchitecture.X86_64}) {
                    all.add(new TargetVariant(os, arch));
                }
            }
            ALL_VARIANTS = List.copyOf(all);
        }
        
        public String variantName(boolean runtime) {
            if (runtime && operatingSystemFamily.equals(OperatingSystemFamily.WINDOWS) && machineArchitecture.equals(MachineArchitecture.X86)) {
                return "runtime";
            }
            return String.format("%s_%s_%s", runtime ? "runtime" : "source", operatingSystemFamily, machineArchitecture);
        }
        
        public static List<TargetVariant> matching(@Nullable String operatingSystemFamily, @Nullable String machineArchitecture) {
            var list = new ArrayList<TargetVariant>();
            for (var target : ALL_VARIANTS) {
                if ((operatingSystemFamily == null || target.operatingSystemFamily.equals(operatingSystemFamily)) &&
                    (machineArchitecture == null || target.machineArchitecture.equals(machineArchitecture))) {
                    list.add(target);
                }
            }
            return List.copyOf(list);
        }
    }
    
    private static final String URL_PREFIX = "https://files.pythonhosted.org/packages/";
    
    @Override
    public void execute(ComponentMetadataContext context) {
        var details = context.getDetails();
        var id = details.getId();
        if (!"pypi".equals(id.getGroup())) {
            return;
        }
        
        details.withVariant("runtime", v -> {
            v.withFiles(MutableVariantFilesMetadata::removeAllFiles);
        });
        for (var target : TargetVariant.ALL_VARIANTS) {
            details.maybeAddVariant(target.variantName(true), null, v -> {
                v.attributes(attributes -> {
                    attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, getObjects().named(OperatingSystemFamily.class, target.operatingSystemFamily()));
                    attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, getObjects().named(MachineArchitecture.class, target.machineArchitecture()));
                    attributes.attribute(Category.CATEGORY_ATTRIBUTE, getObjects().named(Category.class, Category.LIBRARY));
                    attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, getObjects().named(LibraryElements.class, "python-wheel"));
                });
            });
            details.maybeAddVariant(target.variantName(false), null, v -> {
                v.attributes(attributes -> {
                    attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, getObjects().named(OperatingSystemFamily.class, target.operatingSystemFamily()));
                    attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, getObjects().named(MachineArchitecture.class, target.machineArchitecture()));
                    attributes.attribute(Category.CATEGORY_ATTRIBUTE, getObjects().named(Category.class, Category.DOCUMENTATION));
                    attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, getObjects().named(DocsType.class, DocsType.SOURCES));
                });
            });
        }
        getResources().withResource(String.format("%s/%s/json", id.getName(), id.getVersion()), is -> {
            var metadata = PyPIMetadata.fromJson(is);
            metadata.info().parsedRequirements().forEach(requirement -> {
                for (var target : TargetVariant.matching(requirement.operatingSystemFamily(), requirement.machineArchitecture())) {
                    Action<VariantMetadata> addDependencies = v -> {
                        v.withDependencies(dependencies -> {
                            dependencies.add("pypi:"+requirement.name(), dep -> dep.version(version -> {
                                if (requirement.versionSpec() != null) {
                                    requirement.versionSpec().constraints().apply(version);
                                } else {
                                    version.strictly("+");
                                }
                            }));
                        });
                    };
                    details.withVariant(target.variantName(true), addDependencies);
                    details.withVariant(target.variantName(false), addDependencies);
                }
            });
            metadata.parsedUrlInfo(id).forEach(info -> {
                for (var target : TargetVariant.matching(info.operatingSystemFamily(id), info.machineArchitecture(id))) {
                    Action<VariantMetadata> addFile = v -> {
                        v.withDependencies(dependencies -> {
                            if (dependencies.stream().anyMatch(it -> it.getGroup().startsWith(EXTRACT_EXTENSION_PREFIX))) {
                                return;
                            }
                            if (!info.url().startsWith(URL_PREFIX)) {
                                throw new IllegalStateException("Unexpected URL: " + info.url());
                            }
                            var rest = info.url().substring(URL_PREFIX.length());
                            dependencies.add(EXTRACT_EXTENSION_PREFIX+"org.files.pythonhosted:"+rest+":"+id.getVersion());
                        });
                    };
                    if (info.packageType().equals("bdist_wheel")) {
                        details.withVariant(target.variantName(true), addFile);
                    } else if (info.packageType().equals("sdist")) {
                        details.withVariant(target.variantName(false), addFile);
                    }
                }
            });
        });
    }
}
