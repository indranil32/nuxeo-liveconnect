/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 *     Nelson Silva
 */
package org.nuxeo.ecm.liveconnect.google.drive;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.api.services.drive.DriveRequest;
import com.google.api.services.drive.model.App;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ObjectParser;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.blob.BlobManager.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobManager.UsageHint;
import org.nuxeo.ecm.core.blob.ExtendedBlobProvider;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.blob.SimpleManagedBlob;
import org.nuxeo.ecm.core.blob.apps.AppLink;
import org.nuxeo.ecm.core.blob.apps.LinkedAppsProvider;
import org.nuxeo.ecm.core.cache.Cache;
import org.nuxeo.ecm.core.cache.CacheService;
import org.nuxeo.ecm.core.model.Document;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.liveconnect.google.drive.credential.CredentialFactory;
import org.nuxeo.ecm.liveconnect.google.drive.credential.ServiceAccountCredentialFactory;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import org.nuxeo.ecm.liveconnect.google.drive.credential.OAuth2CredentialFactory;

import org.nuxeo.ecm.liveconnect.update.BatchUpdateBlobProvider;
import org.nuxeo.ecm.liveconnect.update.worker.BlobProviderDocumentsUpdateWork;
import org.nuxeo.ecm.platform.oauth2.providers.OAuth2ServiceProvider;
import org.nuxeo.ecm.platform.oauth2.providers.OAuth2ServiceProviderRegistry;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.platform.query.nxql.NXQLQueryBuilder;
import org.nuxeo.runtime.api.Framework;

/**
 * Provider for blobs getting information from Google Drive.
 *
 * @since 7.3
 */
public class GoogleDriveBlobProvider implements ExtendedBlobProvider, BatchUpdateBlobProvider, LinkedAppsProvider {

    private static final Log log = LogFactory.getLog(GoogleDriveBlobProvider.class);

    public static final String PREFIX = "googledrive";

    private static final String APPLICATION_NAME = "Nuxeo/0";

    private static final String FILE_CACHE_NAME = "googleDrive";

    // Service account details
    public static final String SERVICE_ACCOUNT_ID_PROP = "serviceAccountId";

    public static final String SERVICE_ACCOUNT_P12_PATH_PROP = "serviceAccountP12Path";

    // ClientId for the file picker auth
    public static final String CLIENT_ID_PROP = "clientId";

    protected static final String DOCUMENT_TO_BE_UPDATED_QUERY = String.format(
            "SELECT * FROM Document WHERE content/data LIKE '%s:%%' AND ecm:isVersion = 0 ORDER BY ecm:uuid ASC",
            PREFIX);

    protected static final ObjectParser parser = new JsonObjectParser(JacksonFactory.getDefaultInstance());

    private String serviceAccountId;

    private java.io.File serviceAccountP12File;

    private String clientId;

    /** resource cache */
    private Cache cache;

    @Override
    public void initialize(String blobProviderId, Map<String, String> properties) throws IOException {
        if (!PREFIX.equals(blobProviderId)) {
            // TODO avoid this by passing a parameter to the GoogleDriveBlobUploader when constructed
            throw new IllegalArgumentException("Must be registered for name: " + PREFIX + ", not: " + blobProviderId);
        }
        // Validate service account configuration
        serviceAccountId = properties.get(SERVICE_ACCOUNT_ID_PROP);
        if (StringUtils.isBlank(serviceAccountId)) {
            return;
        }
        String p12 = properties.get(SERVICE_ACCOUNT_P12_PATH_PROP);
        if (StringUtils.isBlank(p12)) {
            throw new NuxeoException("Missing value for property: " + SERVICE_ACCOUNT_P12_PATH_PROP);
        }
        serviceAccountP12File = new java.io.File(p12);
        if (!serviceAccountP12File.exists()) {
            throw new NuxeoException("No such file: " + p12 + " for property: " + SERVICE_ACCOUNT_P12_PATH_PROP);
        }

        clientId = properties.get(CLIENT_ID_PROP);
        if (StringUtils.isBlank(clientId)) {
            throw new NuxeoException("Missing value for property: " + CLIENT_ID_PROP);
        }
    }

    @Override
    public void close() {
    }

    @Override
    public Blob readBlob(BlobInfo blobInfo) {
        return new SimpleManagedBlob(blobInfo);
    }

    @Override
    public String writeBlob(Blob blob, Document doc) {
        throw new UnsupportedOperationException("Writing a blob to Google Drive is not supported");
    }

    @Override
    public URI getURI(ManagedBlob blob, UsageHint usage) throws IOException {
        String url = null;
        File file = getFile(blob);
        switch (usage) {
        case STREAM:
            url = file.getDownloadUrl();
            break;
        case DOWNLOAD:
            url = file.getWebContentLink();
            if (url == null) {
                url = file.getAlternateLink();
            }
            break;
        case VIEW:
        case EDIT:
            url = file.getAlternateLink();
            break;
        case EMBED:
            url = file.getEmbedLink();
            if (url == null) {
                // non-native file resources do not return an embedLink but it is available
                url = file.getAlternateLink();
                URI uri = asURI(url).resolve("./preview");
                url = uri.toString();
            }
            break;
        }
        return url != null ? asURI(url) : null;
    }

    protected InputStream getStream(String blobKey, URI uri) throws IOException {
        String info = getFileInfo(blobKey);
        String user = getUser(info);
        return doGet(user, uri);
    }

    @Override
    public Map<String, URI> getAvailableConversions(ManagedBlob blob, UsageHint hint) throws IOException {
        File file = getFile(blob);
        Map<String, String> exportLinks = file.getExportLinks();
        if (exportLinks == null) {
            return Collections.emptyMap();
        }
        Map<String, URI> conversions = new HashMap<>();
        for (String mimeType : exportLinks.keySet()) {
            conversions.put(mimeType, asURI(exportLinks.get(mimeType)));
        }
        return conversions;
    }

    @Override
    public InputStream getThumbnail(ManagedBlob blob) throws IOException {
        URI uri = asURI(getFile(blob).getThumbnailLink());
        return getStream(blob.getKey(), uri);
    }

    @Override
    public InputStream getStream(ManagedBlob blob) throws IOException {
        URI uri = getURI(blob, UsageHint.STREAM);
        return uri == null ? null : getStream(blob.getKey(), uri);
    }

    @Override
    public InputStream getConvertedStream(ManagedBlob blob, String mimeType) throws IOException {
        Map<String, URI> conversions = getAvailableConversions(blob, UsageHint.STREAM);
        URI uri = conversions.get(mimeType);
        if (uri == null) {
            return null;
        }
        return getStream(blob.getKey(), uri);
    }

    @Override
    public List<AppLink> getAppLinks(String username, ManagedBlob blob) throws IOException {
        List<AppLink> appLinks = new ArrayList<>();

        String fileInfo = getFileInfo(blob.getKey());
        String fileId = getFileId(fileInfo);

        // retrieve the service's user (email in this case) for this username
        String user = getServiceUser(username);

        // fetch a partial file response
        File file = getPartialFile(user, fileId, "openWithLinks", "defaultOpenWithLink");
        if (file.isEmpty()) {
            return appLinks;
        }

        // build the list of AppLinks
        String defaultLink = file.getDefaultOpenWithLink();
        for (Map.Entry<String, String> entry : file.getOpenWithLinks().entrySet()) {
            // build the AppLink
            App app = getApp(user, entry.getKey());
            AppLink appLink = new AppLink();
            appLink.setAppName(app.getName());
            appLink.setLink(asURI(entry.getValue()));

            // pick an application icon
            for (com.google.api.services.drive.model.App.Icons icon : app.getIcons()) {
                if ("application".equals(icon.getCategory())) {
                    appLink.setIcon(icon.getIconUrl());
                    // break if we've got one with our preferred size
                    if (icon.getSize() == PREFERRED_ICON_SIZE) {
                        break;
                    }
                }
            }

            // add the default link first
            if (defaultLink != null && defaultLink.equals(entry.getValue())) {
                appLinks.add(0, appLink);
            } else {
                appLinks.add(appLink);
            }
        }
        return appLinks;
    }

    protected String getServiceUser(String username) {
        CredentialFactory credentialFactory = getCredentialFactory();
        if (credentialFactory instanceof OAuth2CredentialFactory) {
            OAuth2ServiceProvider provider = ((OAuth2CredentialFactory) credentialFactory).getProvider();
            return ((GoogleOAuth2ServiceProvider) provider).getServiceUser(username);
        } else {
            UserManager userManager = Framework.getLocalService(UserManager.class);
            DocumentModel user = userManager.getUserModel(username);
            if (user == null) {
                return null;
            }
            return (String) user.getPropertyValue(userManager.getUserEmailField());
        }
    }

    protected App getApp(String user, String appId) throws IOException {
        return executeRequest("app_" + appId, getService(user).apps().get(appId), App.class);
    }

    /**
     * Gets the blob for a Google Drive file.
     *
     * @param fileInfo the file info ({email}:{fileId})
     * @return the blob
     */
    protected Blob getBlob(String fileInfo) throws IOException {
        String user = getUser(fileInfo);
        String fileId = getFileId(fileInfo);
        File file = getFile(user, fileId);
        String key = String.format("%s:%s:%s", PREFIX, user, fileId);
        String filename = file.getOriginalFilename();
        if (filename == null) {
            filename = file.getTitle().replace("/", "-");
        }
        BlobInfo blobInfo = new BlobInfo();
        blobInfo.key = key;
        blobInfo.mimeType = file.getMimeType();
        blobInfo.encoding = null; // TODO extract from mimeType
        blobInfo.filename = filename;
        blobInfo.length = file.getFileSize();
        // etag for native docs and md5 for everything else
        String digest = getDigest(file);
        blobInfo.digest = digest;
        return new SimpleManagedBlob(blobInfo);
    }

    protected String getDigest(File file) {
        String digest = file.getMd5Checksum();
        if (digest == null) {
            digest = file.getEtag();
        }
        return digest;
    }

    protected boolean isDigestChanged(SimpleManagedBlob blob, File file) {
        final String digest = blob.getDigest();
        String md5CheckSum = file.getMd5Checksum();
        String eTag = file.getEtag();
        if (md5CheckSum != null) {
            return !md5CheckSum.equals(digest);
        } else {
            return eTag != null && !eTag.equals(digest);
        }
    }

    /** Removes the prefix from the key. */
    protected String getFileInfo(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(key);
        }
        String fileInfo = key.substring(colon + 1);
        return fileInfo;
    }

    protected String getUser(String fileInfo) {
        return getFileInfoParts(fileInfo)[0];
    }

    protected String getFileId(String fileInfo) {
        return getFileInfoParts(fileInfo)[1];
    }

    protected String[] getFileInfoParts(String fileInfo) {
        String[] parts = fileInfo.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException(fileInfo);
        }
        return parts;
    }

    protected Credential getCredential(String user) throws IOException {
        return getCredentialFactory().build(user);
    }

    protected CredentialFactory getCredentialFactory() {
        OAuth2ServiceProvider provider = Framework.getLocalService(OAuth2ServiceProviderRegistry.class).getProvider(
                PREFIX);
        if (provider != null && provider.isEnabled()) {
            // Web application configuration
            return new OAuth2CredentialFactory(provider);
        } else {
            // Service account configuration
            return new ServiceAccountCredentialFactory(serviceAccountId, serviceAccountP12File);
        }
    }

    protected Drive getService(String user) throws IOException {
        Credential credential = getCredential(user);
        HttpTransport httpTransport = credential.getTransport();
        JsonFactory jsonFactory = credential.getJsonFactory();
        return new Drive.Builder(httpTransport, jsonFactory, credential) //
        .setApplicationName(APPLICATION_NAME) // set application name to avoid a WARN
        .build();
    }

    protected File getFile(ManagedBlob blob) throws IOException {
        String fileInfo = getFileInfo(blob.getKey());
        String user = getUser(fileInfo);
        String fileId = getFileId(fileInfo);
        return getFile(user, fileId);
    }

    /**
     * Retrieves a {@link File} resource and caches the unparsed response.
     *
     * @return a {@link File} resource.
     */
    protected File getFile(String user, String fileId) throws IOException {
        return executeRequest("file_" + fileId, getService(user).files().get(fileId), File.class);
    }

    /**
     * Executes a {@link DriveRequest} and caches the unparsed response.
     */
    private <T> T executeRequest(String cacheKey, DriveRequest<T> request, Class<T> aClass) throws IOException {
        String resource = (String) getCache().get(cacheKey);

        if (resource == null) {
            HttpResponse response = request.executeUnparsed();
            if (!response.isSuccessStatusCode()) {
                return null;
            }
            resource = response.parseAsString();
            if (cacheKey != null) {
                getCache().put(cacheKey, resource);
            }
        }
        return parser.parseAndClose(new StringReader(resource), aClass);
    }

    /**
     * Executes a GET request with the user's credentials.
     */
    protected InputStream doGet(String user, URI url) throws IOException {
        HttpResponse response = getService(user).getRequestFactory().buildGetRequest(new GenericUrl(url)).execute();
        return response.getContent();
    }

    /**
     * Parse a {@link URI}.
     *
     * @return the {@link URI} or null if it fails
     */
    protected static URI asURI(String link) {
        try {
            return new URI(link);
        } catch (URISyntaxException e) {
            log.error("Invalid URI: " + link, e);
            return null;
        }
    }

    protected Cache getCache() {
        if (cache == null) {
            cache = Framework.getService(CacheService.class).getCache(FILE_CACHE_NAME);
        }
        return cache;
    }

    public String getClientId() {
        OAuth2ServiceProvider provider = getOAuth2Provider();
        return (provider != null && provider.isEnabled()) ? provider.getClientId() : clientId;
    }

    private OAuth2ServiceProvider getOAuth2Provider() {
        return Framework.getLocalService(OAuth2ServiceProviderRegistry.class).getProvider(PREFIX);
    }

    @Override
    public List<DocumentModel> checkChangesAndUpdateBlob(List<DocumentModel> docs) {
        List<DocumentModel> changedDocuments = new ArrayList<>();
        // TODO use google batch request here
        for (DocumentModel doc : docs) {
            final SimpleManagedBlob blob = (SimpleManagedBlob) doc.getProperty("content").getValue();
            String fileInfo = getFileInfo(blob.getKey());
            String user = getUser(fileInfo);
            String fileId = getFileId(fileInfo);
            try {
                File remote = getPartialFile(user, fileId, "id", "etag", "md5Checksum");
                if (isDigestChanged(blob, remote)) {
                    doc.setPropertyValue("content", (SimpleManagedBlob) getBlob(getFileInfo(blob.getKey())));
                    getCache().invalidate("file_" + fileId);
                    changedDocuments.add(doc);
                }
            } catch (IOException e) {
                log.error("Could not update google drive document " + doc.getTitle(), e);
            }

        }
        return changedDocuments;
    }

    /**
     * Retrieve a partial {@link File} resource.
     */
    protected File getPartialFile(String user, String fileId, String... fields) throws IOException {
        return getService(user).files().get(fileId).setFields(StringUtils.join(fields, ",")).execute();
    }

    @Override
    public void processDocumentsUpdate() {
        final RepositoryManager repositoryManager = Framework.getLocalService(RepositoryManager.class);
        final WorkManager workManager = Framework.getLocalService(WorkManager.class);
        for (String repositoryName : repositoryManager.getRepositoryNames()) {
            CoreSession session = null;
            try {
                session = CoreInstance.openCoreSessionSystem(repositoryName);

                long offset = 0;
                List<DocumentModel> nextDocumentsToBeUpdated;
                do {
                    nextDocumentsToBeUpdated = getNextDocumentToBeUpdatedResults(session, offset);
                    if (nextDocumentsToBeUpdated.isEmpty()) {
                        break;
                    }
                    List<String> docIds = new ArrayList<>();
                    for (DocumentModel doc : nextDocumentsToBeUpdated) {
                        docIds.add(doc.getId());
                    }
                    BlobProviderDocumentsUpdateWork work = new BlobProviderDocumentsUpdateWork(
                            "googledrive:" + repositoryName + ":" + offset, "googledrive");
                    work.setDocuments(repositoryName, docIds);
                    workManager.schedule(work, WorkManager.Scheduling.IF_NOT_SCHEDULED, true);
                    offset += MAX_RESULT;
                } while (nextDocumentsToBeUpdated.size() == MAX_RESULT);

            } finally {
                if (session != null) {
                    session.close();
                }
            }
        }
    }

    private List<DocumentModel> getNextDocumentToBeUpdatedResults(CoreSession session, long offset)
            throws ClientException {
        List<DocumentModel> results;
        String query = NXQLQueryBuilder.getQuery(DOCUMENT_TO_BE_UPDATED_QUERY, null, false, false, null);
        results = session.query(query, null, MAX_RESULT, offset, MAX_RESULT);
        return results;
    }

}