package fr.samar.translation;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.DocumentLocation;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.work.AbstractWork;
import org.nuxeo.ecm.platform.commandline.executor.api.CmdParameters;
import org.nuxeo.ecm.platform.commandline.executor.api.CommandAvailability;
import org.nuxeo.ecm.platform.commandline.executor.api.CommandLineExecutorService;
import org.nuxeo.ecm.platform.commandline.executor.api.CommandNotAvailable;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Handle the asynchronous translation of document fields using document
 * adaptors to make it possible to override text content is to be extracted for
 * each document type.
 * 
 * The translation process is handled by a command line executor that works out
 * of any transactional context to avoid transaction timeout issues.
 */
public class TranslationWork extends AbstractWork {

    private static final Log log = LogFactory.getLog(TranslationWork.class);

    protected static final String EVENT_TRANSLATION_COMPLETE = null;

    protected final DocumentLocation docLoc;

    protected final Set<String> targetLanguages = new LinkedHashSet<String>();

    public TranslationWork(DocumentLocation docLoc) {
        this.docLoc = docLoc;
    }

    public TranslationWork withTargetLanguage(String language) {
        targetLanguages.add(language);
        return this;
    }

    @Override
    public String getTitle() {
        return String.format("Translation for: %s:%s", docLoc.getServerName(),
                docLoc.getDocRef());
    }

    @Override
    public void work() throws Exception {
        setProgress(Progress.PROGRESS_INDETERMINATE);
        TranslationTask task = makeTranslationTask();

        // Release the current transaction as the following calls will be very
        // long and won't need access to any persistent transactional resources
        if (isTransactional()) {
            TransactionHelper.commitOrRollbackTransaction();
        }
        File tempFolder = File.createTempFile("nuxeo_translation_", "_tmp");
        tempFolder.delete();
        tempFolder.mkdir();
        try {
            for (String targetLanguage : targetLanguages) {
                for (Map<String, Object> subTask : task.getFieldsToTranslate()) {
                    if (isSuspending()) {
                        return;
                    }
                    String translated = performTranslation(
                            task.getSourceLanguage(),
                            targetLanguage,
                            (String) subTask.get(TranslationAdapter.PROPERTY_PATH),
                            (String) subTask.get(TranslationAdapter.TEXT),
                            (Boolean) subTask.get(TranslationAdapter.IS_FORMATTED),
                            tempFolder);
                    Map<String, Object> translationResult = new HashMap<String, Object>();
                    translationResult.put(TranslationAdapter.TEXT, translated);
                    translationResult.put(TranslationAdapter.PROPERTY_PATH,
                            subTask.get(TranslationAdapter.PROPERTY_PATH));
                    translationResult.put(TranslationTask.LANGUAGE,
                            targetLanguage);
                    task.addTranslationResult(translationResult);
                }
            }
        } finally {
            FileUtils.deleteQuietly(tempFolder);
        }

        // Save the results back on the document in a new, short-lived
        // transaction
        if (isTransactional()) {
            TransactionHelper.startTransaction();
        }
        setStatus("saving_results");
        saveResults(task);
    }

    protected TranslationTask makeTranslationTask() throws ClientException {
        final TranslationTask[] results = new TranslationTask[] { null };
        final DocumentRef docRef = docLoc.getDocRef();
        String repositoryName = docLoc.getServerName();
        new UnrestrictedSessionRunner(repositoryName) {
            @Override
            public void run() throws ClientException {
                if (session.exists(docRef)) {
                    DocumentModel doc = session.getDocument(docRef);
                    TranslationAdapter adapter = doc.getAdapter(
                            TranslationAdapter.class, true);
                    if (adapter != null) {
                        results[0] = adapter.getTranslationTask();
                    }
                }
            }
        }.runUnrestricted();
        return results[0];
    }

    protected String performTranslation(String sourceLanguage,
            String targetLanguage, String fieldName, String text,
            boolean isFormatted, File tempFolder) throws IOException,
            ClientException, CommandNotAvailable {
        if (text == null || text.trim().isEmpty()) {
            // Nothing to translate
            return "";
        }
        String format = isFormatted ? "xml" : "txt";
        String baseName = fieldName.replaceAll(":", "__");
        File sourceTextFile = new File(tempFolder, String.format("%s.%s",
                baseName, format));
        FileUtils.writeStringToFile(sourceTextFile, text, "UTF-8");

        String commandName = String.format("translate_%s_%s_%s",
                sourceLanguage, targetLanguage, format);
        CommandLineExecutorService executorService = Framework.getLocalService(CommandLineExecutorService.class);
        CommandAvailability ca = executorService.getCommandAvailability(commandName);
        if (!ca.isAvailable()) {
            if (ca.getInstallMessage() != null) {
                log.error(String.format("%s is not available: %s", commandName,
                        ca.getInstallMessage()));
            } else {
                log.error(ca.getErrorMessage());
            }
            return null;
        }
        CmdParameters params = new CmdParameters();
        params.addNamedParameter("inputFile", sourceTextFile);
        executorService.execCommand(commandName, params);
        File targetTextFile = new File(tempFolder, String.format("%s.%s.%s",
                baseName, targetLanguage, format));
        return FileUtils.readFileToString(targetTextFile, "UTF-8");
    }

    protected void saveResults(final TranslationTask task)
            throws ClientException {
        final DocumentRef docRef = docLoc.getDocRef();
        String repositoryName = docLoc.getServerName();
        new UnrestrictedSessionRunner(repositoryName) {
            @Override
            public void run() throws ClientException {
                if (session.exists(docRef)) {
                    DocumentModel doc = session.getDocument(docRef);
                    TranslationAdapter adapter = doc.getAdapter(
                            TranslationAdapter.class, true);
                    adapter.setTranslationResults(task);
                    session.saveDocument(doc);

//                    // Notify transcription completion to make it possible to
//                    // chain processing.
//                    DocumentEventContext ctx = new DocumentEventContext(
//                            session, getPrincipal(), doc);
//                    ctx.setProperty(CoreEventConstants.REPOSITORY_NAME,
//                            repositoryName);
//                    ctx.setProperty(CoreEventConstants.SESSION_ID,
//                            session.getSessionId());
//                    ctx.setProperty("category",
//                            DocumentEventCategories.EVENT_DOCUMENT_CATEGORY);
//                    Event event = ctx.newEvent(EVENT_TRANSLATION_COMPLETE);
//                    EventService eventService = Framework.getLocalService(EventService.class);
//                    eventService.fireEvent(event);
                }
            }
        }.runUnrestricted();
    }

    @Override
    public int hashCode() {
        return docLoc.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        TranslationWork other = (TranslationWork) obj;
        if (docLoc == null) {
            if (other.docLoc != null) {
                return false;
            }
        } else if (!docLoc.equals(other.docLoc)) {
            return false;
        }
        return true;
    }

}
