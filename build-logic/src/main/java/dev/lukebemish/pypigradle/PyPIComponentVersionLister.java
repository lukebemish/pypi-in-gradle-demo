package dev.lukebemish.pypigradle;

import org.gradle.api.artifacts.ComponentMetadataListerDetails;
import org.gradle.api.artifacts.ComponentMetadataVersionLister;
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor;

import javax.inject.Inject;

public abstract class PyPIComponentVersionLister implements ComponentMetadataVersionLister {
    @Inject
    public PyPIComponentVersionLister() {}

    @Inject
    protected abstract RepositoryResourceAccessor getResources();
    
    @Override
    public void execute(ComponentMetadataListerDetails details) {
        var name = details.getModuleIdentifier().getName();
        getResources().withResource(String.format("%s/json", name), is -> {
            var metadata = PyPIIndexMetadata.fromJson(is);
            details.listed(metadata.releases().keySet().stream().toList());
        });
    }
}
