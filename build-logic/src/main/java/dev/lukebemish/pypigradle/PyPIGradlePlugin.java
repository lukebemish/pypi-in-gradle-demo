package dev.lukebemish.pypigradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class PyPIGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getRepositories().exclusiveContent(exclusive -> {
            exclusive.forRepositories(project.getRepositories().ivy(repository -> {
                repository.setUrl("https://pypi.org/pypi/");
                repository.patternLayout(layout -> {
                    layout.artifact("[module]/[revision]/json");
                });
                repository.metadataSources(sources -> {
                    sources.artifact();
                });
                repository.setComponentVersionsLister(PyPIComponentVersionLister.class);
            }));
            exclusive.filter(content -> {
                content.includeGroup("pypi");
            });
        });

        project.getRepositories().exclusiveContent(exclusive -> {
            exclusive.forRepositories(project.getRepositories().ivy(repository -> {
                repository.setUrl("https://files.pythonhosted.org/packages");
                repository.patternLayout(layout -> {
                    layout.artifact("[module].[ext]");
                });
                repository.metadataSources(sources -> {
                    sources.artifact();
                });
            }));
            exclusive.filter(content -> {
                content.includeGroup("org.files.pythonhosted");
            });
        });

        project.getConfigurations().configureEach(config -> {
            config.getResolutionStrategy().eachDependency(details -> {
                if (details.getRequested().getGroup().startsWith(EXTRACT_EXTENSION_PREFIX)) {
                    var name = details.getRequested().getName();
                    String extension;
                    if (name.endsWith(".tar.gz")) {
                        extension = "tar.gz";
                    } else {
                        var lastIndex = name.lastIndexOf('.');
                        extension = name.substring(lastIndex + 1);
                        name = name.substring(0, lastIndex);
                    }
                    details.useTarget(String.format(
                            "%s:%s:%s",
                            details.getRequested().getGroup().substring(EXTRACT_EXTENSION_PREFIX.length()),
                            name,
                            details.getRequested().getVersion()
                    ));
                    details.artifactSelection(selection -> {
                        selection.selectArtifact(extension, extension, null);
                    });
                }
            });
        });
        
        project.getDependencies().getComponents().all(PyPIComponentRule.class);
    }
    
    public static final String EXTRACT_EXTENSION_PREFIX = "_extract-extension.";
}
