/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.app.DBASecureStorage;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.app.DBPWorkspace;
import org.jkiss.dbeaver.model.auth.SMSessionContext;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.task.DBTTaskManager;
import org.jkiss.dbeaver.registry.task.TaskManagerImpl;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public abstract class BaseProjectImpl implements DBPProject {

    private static final Log log = Log.getLog(BaseProjectImpl.class);

    public static final String SETTINGS_STORAGE_FILE = "project-settings.json";
    public static final String METADATA_STORAGE_FILE = "project-metadata.json";
    public static final String PROP_PROJECT_ID = "id";

    public enum ProjectFormat {
        UNKNOWN,    // Project is not open or corrupted
        LEGACY,     // Old format (before 6.1 version
        MODERN,     // 6.1+ version
    }

    private static final Gson METADATA_GSON = new GsonBuilder()
        .setLenient()
        .serializeNulls()
        .create();

    @NotNull
    private final DBPWorkspace workspace;
    @NotNull
    private final SMSessionContext sessionContext;

    private volatile ProjectFormat format = ProjectFormat.UNKNOWN;
    private volatile DataSourceRegistry dataSourceRegistry;
    private volatile TaskManagerImpl taskManager;
    private volatile Map<String, Object> properties;
    private volatile Map<String, Map<String, Object>> resourceProperties;
    private DBASecureStorage secureStorage;
    private UUID projectID;

    protected final Object metadataSync = new Object();
    private ProjectSyncJob metadataSyncJob;

    private boolean inMemory;

    public BaseProjectImpl(@NotNull DBPWorkspace workspace, @Nullable SMSessionContext sessionContext) {
        this.workspace = workspace;
        this.sessionContext = sessionContext == null ? workspace.getAuthContext() : sessionContext;
    }

    public void setInMemory(boolean inMemory) {
        this.inMemory = inMemory;
    }

    public boolean isInMemory() {
        return inMemory;
    }

    @NotNull
    @Override
    public DBPWorkspace getWorkspace() {
        return workspace;
    }

    @NotNull
    @Override
    public UUID getProjectID() {
        if (projectID == null) {
            String idStr = CommonUtils.toString(this.getProjectProperty(PROP_PROJECT_ID), null);
            if (CommonUtils.isEmpty(idStr)) {
                projectID = UUID.randomUUID();
                this.setProjectProperty(PROP_PROJECT_ID, projectID.toString());
            } else {
                projectID = UUID.fromString(idStr);
            }
        }
        return projectID;
    }

    @Nullable
    @Override
    public IContainer getRootResource() {
        return getEclipseProject();
    }

    @NotNull
    @Override
    public Path getMetadataFolder(boolean create) {
        Path metadataFolder = getMetadataPath();
        if (create && !Files.exists(metadataFolder)) {
            try {
                Files.createDirectories(metadataFolder);
            } catch (IOException e) {
                log.error("Error creating metadata folder" + metadataFolder, e);
            }
        }

        return metadataFolder;
    }

    @NotNull
    protected Path getMetadataPath() {
        return getAbsolutePath().resolve(METADATA_FOLDER);
    }

    @Override
    public boolean isRegistryLoaded() {
        return dataSourceRegistry != null;
    }

    @Override
    public boolean isModernProject() {
        return getFormat() == ProjectFormat.MODERN;
    }

    @NotNull
    @Override
    public DBPDataSourceRegistry getDataSourceRegistry() {
        ensureOpen();
        synchronized (metadataSync) {
            if (dataSourceRegistry == null) {
                dataSourceRegistry = createDataSourceRegistry();
            }
        }
        return dataSourceRegistry;
    }

    @NotNull
    protected DataSourceRegistry createDataSourceRegistry() {
        return new DataSourceRegistry(this);
    }

    @NotNull
    @Override
    public DBTTaskManager getTaskManager() {
        ensureOpen();
        if (taskManager == null) {
            synchronized (metadataSync) {
                if (taskManager == null) {
                    taskManager = new TaskManagerImpl(this);
                }
            }
        }
        return taskManager;
    }

    ////////////////////////////////////////////////////////
    // Secure storage

    @NotNull
    @Override
    public DBASecureStorage getSecureStorage() {
        synchronized (metadataSync) {
            if (this.secureStorage == null) {
                this.secureStorage = workspace.getPlatform().getApplication().getProjectSecureStorage(this);
            }
        }
        return secureStorage;
    }

    @NotNull
    @Override
    public SMSessionContext getSessionContext() {
        return sessionContext;
    }

    ////////////////////////////////////////////////////////
    // Properties

    @Override
    public Object getProjectProperty(String propName) {
        synchronized (this) {
            loadProperties();
            return properties.get(propName);
        }
    }

    @Override
    public void setProjectProperty(String propName, Object propValue) {
        synchronized (metadataSync) {
            loadProperties();
            if (propValue == null) {
                properties.remove(propName);
            } else {
                properties.put(propName, propValue);
            }
            saveProperties();
        }
    }

    private void loadProperties() {
        if (properties != null) {
            return;
        }

        synchronized (metadataSync) {
            Path settingsFile = getMetadataPath().resolve(SETTINGS_STORAGE_FILE);
            if (Files.exists(settingsFile) && settingsFile.toFile().length() > 0) {
                // Parse metadata
                try (Reader settingsReader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
                    properties = JSONUtils.parseMap(METADATA_GSON, settingsReader);
                } catch (Throwable e) {
                    log.error("Error reading project '" + getName() + "' setting from "  + settingsFile.toAbsolutePath(), e);
                }
            }
            if (properties == null) {
                properties = new LinkedHashMap<>();
            }
        }
    }

    private void saveProperties() {
        if (isInMemory()) {
            return;
        }

        Path settingsFile = getMetadataPath().resolve(SETTINGS_STORAGE_FILE);
        String settingsString = METADATA_GSON.toJson(properties);

        try {
            Path configFolder = settingsFile.getParent();
            if (!Files.exists(configFolder)) {
                Files.createDirectories(configFolder);
            }

            Files.writeString(settingsFile, settingsString);
        } catch (Exception e) {
            log.error("Error writing project '" + getName() + "' setting to "  + settingsFile.toAbsolutePath(), e);
        }
    }

    @Override
    public Object getResourceProperty(IResource resource, String propName) {
        loadMetadata();
        synchronized (metadataSync) {
            Map<String, Object> resProps = resourceProperties.get(resource.getProjectRelativePath().toString());
            if (resProps != null) {
                return resProps.get(propName);
            }
        }
        return null;
    }

    @Override
    public Map<String, Object> getResourceProperties(IResource resource) {
        loadMetadata();
        synchronized (metadataSync) {
            return resourceProperties.get(resource.getProjectRelativePath().toString());
        }
    }

    @Override
    public Map<String, Map<String, Object>> getResourceProperties() {
        loadMetadata();
        synchronized (metadataSync) {
            return new LinkedHashMap<>(resourceProperties);
        }
    }

    @Override
    public void setResourceProperty(IResource resource, String propName, Object propValue) {
        loadMetadata();
        synchronized (metadataSync) {
            String filePath = resource.getProjectRelativePath().toString();
            Map<String, Object> resProps = resourceProperties.get(filePath);
            if (resProps == null) {
                if (propValue == null) {
                    // No props + no new value - ignore
                    return;
                }
                resProps = new LinkedHashMap<>();
                resourceProperties.put(filePath, resProps);
            }
            if (propValue == null) {
                if (resProps.remove(propName) == null) {
                    if (resProps.isEmpty()) {
                        resourceProperties.remove(filePath);
                    } else {
                        // No changes
                        return;
                    }
                }
            } else {
                Object oldValue = resProps.put(propName, propValue);
                if (Objects.equals(oldValue, propValue)) {
                    // No changes
                    return;
                }
            }
        }
        flushMetadata();
    }

    @Override
    public void setResourceProperties(IResource resource, Map<String, Object> props) {
        loadMetadata();
        synchronized (metadataSync) {
            String filePath = resource.getProjectRelativePath().toString();
            Map<String, Object> resProps = resourceProperties.get(filePath);
            if (resProps == null) {
                if (props.isEmpty()) {
                    // No props + no new value - ignore
                    return;
                }
                resProps = new LinkedHashMap<>();
                resourceProperties.put(filePath, resProps);
            }
            boolean hasChanges = false;
            for (Map.Entry<String, Object> pe : props.entrySet()) {
                if (pe.getValue() == null) {
                    if (resProps.remove(pe.getKey()) != null) {
                        hasChanges = true;
                    }
                } else {
                    Object oldValue = resProps.get(pe.getKey());
                    if (!CommonUtils.equalObjects(oldValue, pe.getValue())) {
                        resProps.put(pe.getKey(), pe.getValue());
                        hasChanges = true;
                    }
                }
            }
            if (!hasChanges) {
                return;
            }
        }
        flushMetadata();
    }

    protected void setResourceProperties(Map<String, Map<String, Object>> resourceProperties) {
        this.resourceProperties = resourceProperties;
    }

    public void dispose() {
        if (dataSourceRegistry != null) {
            dataSourceRegistry.dispose();
        }
    }

    public ProjectFormat getFormat() {
        return format;
    }

    protected void setFormat(ProjectFormat format) {
        this.format = format;
    }

    private void loadMetadata() {
        if (isInMemory()) {
            return;
        }
        ensureOpen();
        synchronized (metadataSync) {
            if (resourceProperties != null) {
                return;
            }

            Path mdFile = getMetadataPath().resolve(METADATA_STORAGE_FILE);
            if (Files.exists(mdFile) && mdFile.toFile().length() > 0) {
                // Parse metadata
                Map<String, Map<String, Object>> mdCache = new TreeMap<>();
                try (Reader mdReader = Files.newBufferedReader(mdFile, StandardCharsets.UTF_8)) {
                    try (JsonReader jsonReader = METADATA_GSON.newJsonReader(mdReader)) {
                        jsonReader.beginObject();
                        while (jsonReader.hasNext()) {
                            String topName = jsonReader.nextName();
                            if ("resources".equals(topName)) {
                                jsonReader.beginObject();

                                while (jsonReader.hasNext()) {
                                    String resourceName = jsonReader.nextName();
                                    Map<String, Object> resProperties = new HashMap<>();
                                    jsonReader.beginObject();
                                    while (jsonReader.hasNext()) {
                                        String propName = jsonReader.nextName();
                                        Object propValue;
                                        switch (jsonReader.peek()) {
                                            case NUMBER:
                                                propValue = jsonReader.nextDouble();
                                                break;
                                            case BOOLEAN:
                                                propValue = jsonReader.nextBoolean();
                                                break;
                                            case NULL:
                                                propValue = null;
                                                break;
                                            default:
                                                propValue = jsonReader.nextString();
                                                break;
                                        }
                                        resProperties.put(propName, propValue);
                                    }
                                    jsonReader.endObject();
                                    if (!resProperties.isEmpty()) {
                                        mdCache.put(resourceName, resProperties);
                                    }
                                }
                                jsonReader.endObject();
                            }
                        }

                        jsonReader.endObject();

                        resourceProperties = mdCache;
                    }
                } catch (Throwable e) {
                    log.error("Error reading project '" + getName() + "' metadata from "  + mdFile.toAbsolutePath(), e);
                }
            }
            if (resourceProperties == null) {
                resourceProperties = new TreeMap<>();
            }
        }
    }

    protected void flushMetadata() {
        if (inMemory) {
            return;
        }
        synchronized (metadataSync) {
            if (metadataSyncJob == null) {
                metadataSyncJob = new ProjectSyncJob();
            }
            metadataSyncJob.schedule(100);
        }
    }

    void removeResourceFromCache(IPath path) {
        boolean cacheChanged = false;
        synchronized (metadataSync) {
            if (resourceProperties != null) {
                cacheChanged = (resourceProperties.remove(path.toString()) != null);
            }
        }
        if (cacheChanged) {
            flushMetadata();
        }
    }

    void updateResourceCache(IPath oldPath, IPath newPath) {
        boolean cacheChanged = false;
        synchronized (metadataSync) {
            if (resourceProperties != null) {
                Map<String, Object> props = resourceProperties.remove(oldPath.toString());
                if (props != null) {
                    resourceProperties.put(newPath.toString(), props);
                    cacheChanged = true;
                }
            }
        }
        if (cacheChanged) {
            flushMetadata();
        }
    }

    @Override
    public String toString() {
        return getName();
    }

    private class ProjectSyncJob extends AbstractJob {
        ProjectSyncJob() {
            super("Project metadata sync");
        }

        @Override
        protected IStatus run(DBRProgressMonitor monitor) {
            setName("Project '" + BaseProjectImpl.this.getName() + "' sync job");

            ContentUtils.makeFileBackup(getMetadataFolder(false).resolve(METADATA_STORAGE_FILE));

            synchronized (metadataSync) {
                Path mdFile = getMetadataPath().resolve(METADATA_STORAGE_FILE);
                if (CommonUtils.isEmpty(resourceProperties) && !Files.exists(mdFile)) {
                    // Nothing to save and metadata file doesn't exist
                    return Status.OK_STATUS;
                }
                try {
                    if (!CommonUtils.isEmpty(resourceProperties)) {
                        try (Writer mdWriter = Files.newBufferedWriter(mdFile, StandardCharsets.UTF_8)) {
                            try (JsonWriter jsonWriter = METADATA_GSON.newJsonWriter(mdWriter)) {
                                jsonWriter.beginObject();

                                jsonWriter.name("resources");
                                jsonWriter.beginObject();
                                for (Map.Entry<String, Map<String, Object>> resEntry : resourceProperties.entrySet()) {
                                    jsonWriter.name(resEntry.getKey());
                                    jsonWriter.beginObject();
                                    Map<String, Object> resProps = resEntry.getValue();
                                    for (Map.Entry<String, Object> propEntry : resProps.entrySet()) {
                                        jsonWriter.name(propEntry.getKey());
                                        Object value = propEntry.getValue();
                                        if (value == null) {
                                            jsonWriter.nullValue();
                                        } else if (value instanceof Number) {
                                            jsonWriter.value((Number) value);
                                        } else if (value instanceof Boolean) {
                                            jsonWriter.value((Boolean) value);
                                        } else {
                                            jsonWriter.value(CommonUtils.toString(value));
                                        }
                                    }
                                    jsonWriter.endObject();
                                }
                                jsonWriter.endObject();

                                jsonWriter.endObject();
                                jsonWriter.flush();
                            }
                        }
                    }

                } catch (IOException e) {
                    log.error("Error flushing project metadata", e);
                }
            }

            return Status.OK_STATUS;
        }
    }
}
