package com.norconex.committer.solr;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.common.SolrInputDocument;

import com.norconex.committer.BatchableCommitter;
import com.norconex.committer.CommitterException;
import com.norconex.committer.FileSystemCommitter;
import com.norconex.commons.lang.config.ConfigurationLoader;
import com.norconex.commons.lang.config.IXMLConfigurable;
import com.norconex.commons.lang.io.FileUtil;
import com.norconex.commons.lang.io.IFileVisitor;
import com.norconex.commons.lang.map.Properties;

/**
 * Commits documents to Apache Solr.
 * <p>
 * XML configuration usage:
 * </p>
 * <pre>
 *  &lt;committer class="com.norconex.committer.solr.SolrCommitter"&gt;
 *      &lt;solrURL&gt;(URL to Solr)&lt;/solrURL&gt;
 *      &lt;idSourceField&gt;
 *         (Name of source field that will be mapped to the Solr "id" field.
 *          Default is the document reference the Committer stores
 *          as "committer.reference")
 *      &lt;/idSourceField&gt;
 *      &lt;contentTargetField&gt;
 *         (Solr target field name for a document content/body.
 *          Default is: content)
 *      &lt;/contentTargetField&gt;
 *      &lt;queueDir&gt;(optional path where to queue files)&lt;/queueDir&gt;
 *      &lt;batchSize&gt;(queue size before sending to Solr)&lt;/batchSize&gt;
 *      &lt;solrBatchSize&gt;
 *          (max number of docs to send Solr at once)
 *      &lt;/solrBatchSize&gt;
 *  &lt;/committer&gt;
 * </pre>
 * @author <a href="mailto:pascal.essiembre@norconex.com">Pascal Essiembre</a>
 */
public class SolrCommitter extends BatchableCommitter 
        implements IXMLConfigurable {

    private static final long serialVersionUID = -842307672980791980L;

    private static final Logger LOG = 
            LogManager.getLogger(SolrCommitter.class);
    public static final String DEFAULT_QUEUE_DIR = "./queue";
    public static final int DEFAULT_SOLR_BATCH_SIZE = 100;
    
    private String idSourceField;
    private String contentTargetField;
    private String solrURL;
    private int solrBatchSize = DEFAULT_SOLR_BATCH_SIZE;
    private final FileSystemCommitter queue = new FileSystemCommitter();
    
    private static final FileFilter NON_META_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return !pathname.getName().endsWith(".meta");
        }
    };
    
    public SolrCommitter() {
        super();
    }

    public String getQueueDir() {
        return queue.getDirectory();
    }
    public void setQueueDir(String queueDir) {
        this.queue.setDirectory(queueDir);
    }
    public String getIdSourceField() {
        return idSourceField;
    }
    public void setIdSourceField(String idSourceField) {
        this.idSourceField = idSourceField;
    }
    public String getSolrURL() {
        return solrURL;
    }
    public void setSolrURL(String solrURL) {
        this.solrURL = solrURL;
    }
    public int getSolrBatchSize() {
        return solrBatchSize;
    }
    public void setSolrBatchSize(int solrBatchSize) {
        this.solrBatchSize = solrBatchSize;
    }
    public String getContentTargetField() {
        return contentTargetField;
    }
    public void setContentTargetField(String contentTargetField) {
        this.contentTargetField = contentTargetField;
    }

    @Override
    public void commit() {
        if (StringUtils.isBlank(solrURL)) {
            throw new CommitterException("Solr URL is undefined.");
        }
        final SolrServer server = new HttpSolrServer(solrURL);
        final MutableInt count = new MutableInt(0);
        
        //--- Additions ---
        final Map<File, SolrInputDocument> docsToAdd = 
                new HashMap<File, SolrInputDocument>();
        FileUtil.visitAllFiles(queue.getAddDir(), new IFileVisitor() {
            @Override
            public void visit(File file) {
                try {
                    addDocument(docsToAdd, file);
                } catch (IOException e) {
                    throw new CommitterException(
                            "Cannot create Solr Document for file: "
                                    + file, e);
                }
                count.increment();
                if (count.intValue() % solrBatchSize == 0) {
                    persistToSolr(server, docsToAdd);
                }
            }
        }, NON_META_FILTER);
        if (!docsToAdd.isEmpty()) {
            persistToSolr(server, docsToAdd);
        }
        
        //--- Deletions ---
        count.setValue(0);
        final Map<File, String> docsToRemove = 
                new HashMap<File, String>();
        FileUtil.visitAllFiles(queue.getRemoveDir(), new IFileVisitor() {
            @Override
            public void visit(File file) {
                try {
                    docsToRemove.put(file, org.apache.commons.io.FileUtils
                                    .readFileToString(file));
                } catch (IOException e) {
                    throw new CommitterException(
                            "Cannot read reference from : " + file, e);
                }
                count.increment();
                if (count.intValue() % solrBatchSize == 0) {
                    deleteFromSolr(server, docsToRemove);
                }
            }
        });
        if (!docsToRemove.isEmpty()) {
            deleteFromSolr(server, docsToRemove);
        }
    }

    private void persistToSolr(
            SolrServer server, Map<File, SolrInputDocument> docList) {
        LOG.info("Sending " + docList.size() 
                + " documents to Solr for update.");
        try {
            server.add(docList.values());
            server.commit();
            for (File file : docList.keySet()) {
                file.delete();
                new File(file.getAbsolutePath() + ".meta").delete();
            }
            docList.clear();
        } catch (Exception e) {
            throw new CommitterException(
                    "Cannot index document batch to Solr.", e);
        }
        LOG.info("Done sending documents to Solr for update.");
    }
    private void deleteFromSolr(
            SolrServer server, Map<File, String> docList) {
        LOG.info("Sending " + docList.size() 
                + " documents to Solr for deletion.");
        try {
            server.deleteById(new ArrayList<String>(docList.values()));
            server.commit();
            for (File file : docList.keySet()) {
                file.delete();
            }
            docList.clear();
        } catch (Exception e) {
            throw new CommitterException(
                    "Cannot delete document batch from Solr.", e);
        }
        LOG.info("Done sending documents to Solr for deletion.");
    }
    
    private void addDocument(Map<File, SolrInputDocument> docList, File file) 
            throws IOException {
        Properties metadata = new Properties();
        File metaFile = new File(file.getAbsolutePath() + ".meta");
        if (metaFile.exists()) {
            FileInputStream is = new FileInputStream(metaFile);
            metadata.load(is);
            IOUtils.closeQuietly(is);
        }
        
        SolrInputDocument doc = new SolrInputDocument();
        
        String idField = getIdSourceField();
        if (StringUtils.isBlank(idField)) {
            idField = FileSystemCommitter.DOC_REFERENCE;
        }
        doc.addField("id", metadata.getString(idField));
        
        
        
//        // Make sure there is only 1 content type:
//        String contentType = metadata.getPropertyValue("content-type");
//        metadata.setValue("content-type", contentType);
//        String mimeType = contentType.replaceFirst("(.*?)(;.*)", "$1");
//        metadata.setValue("mime-type", mimeType);
        
        for (String name : metadata.keySet()) {
            for (String value : metadata.get(name)) {
                doc.addField(name, value);
            }
        }
        FileReader reader = new FileReader(file);
        BufferedReader in = new BufferedReader(reader);
        String line = null;
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        while ((line = in.readLine()) != null) {
            out.println(StringEscapeUtils.escapeXml(
                    line.replaceAll("<.*?>", " ")));
        }
        
        String contentField = getContentTargetField();
        if (StringUtils.isBlank(contentField)) {
            contentField = "content";
        }
        doc.addField(contentField, sw.toString());
        out.close();
        
        docList.put(file, doc);

        // To immediately commit after adding documents, you could use: 
//      UpdateRequest req = new UpdateRequest(); 
//      req.setAction( UpdateRequest.ACTION.COMMIT, false, false );
//      req.add( docs );
//      UpdateResponse rsp = req.process( server );  
        
    }

    @Override
    protected void queueBatchableAdd(
            String reference, File document, Properties metadata) {
        queue.queueAdd(reference, document, metadata);
    }

    @Override
    protected void queueBatchableRemove(
            String ref, File document, Properties metadata) {
        queue.queueRemove(ref, document, metadata);
    }

    @Override
    public void loadFromXML(Reader in) {
        XMLConfiguration xml = ConfigurationLoader.loadXML(in);
        setIdSourceField(xml.getString("idSourceField", null));
        setContentTargetField(xml.getString("contentTargetField", null));
        setSolrURL(xml.getString("solrURL", null));
        setQueueDir(xml.getString("queueDir", DEFAULT_QUEUE_DIR));
        setBatchSize(xml.getInt(
                "batchSize", BatchableCommitter.DEFAULT_BATCH_SIZE));
        setSolrBatchSize(xml.getInt("solrBatchSize", DEFAULT_SOLR_BATCH_SIZE));
    }
    @Override
    public void saveToXML(Writer out) throws IOException {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        try {
            XMLStreamWriter writer = factory.createXMLStreamWriter(out);
            writer.writeStartElement("committer");
            writer.writeAttribute("class", getClass().getCanonicalName());

            writer.writeStartElement("idSourceField");
            writer.writeCharacters(idSourceField);
            writer.writeEndElement();

            writer.writeStartElement("contentTargetField");
            writer.writeCharacters(contentTargetField);
            writer.writeEndElement();

            writer.writeStartElement("solrURL");
            writer.writeCharacters(solrURL);
            writer.writeEndElement();

            writer.writeStartElement("queueDir");
            writer.writeCharacters(getQueueDir());
            writer.writeEndElement();

            writer.writeStartElement("batchSize");
            writer.writeCharacters(ObjectUtils.toString(getBatchSize()));
            writer.writeEndElement();

            writer.writeStartElement("solrBatchSize");
            writer.writeCharacters(ObjectUtils.toString(getSolrBatchSize()));
            writer.writeEndElement();
            
            writer.writeEndElement();
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("Cannot save as XML.", e);
        }
    }
}
