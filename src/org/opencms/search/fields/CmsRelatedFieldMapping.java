
package org.opencms.search.fields;

import org.opencms.file.CmsFile;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsProperty;
import org.opencms.file.CmsResource;
import org.opencms.i18n.CmsLocaleManager;
import org.opencms.json.JSONObject;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.search.CmsSearchException;
import org.opencms.search.CmsSearchResource;
import org.opencms.search.CmsSearchUtil;
import org.opencms.search.documents.CmsIndexNoContentException;
import org.opencms.search.documents.Messages;
import org.opencms.search.extractors.I_CmsExtractionResult;
import org.opencms.search.solr.CmsSolrIndex;
import org.opencms.search.solr.CmsSolrQuery;
import org.opencms.search.solr.CmsSolrResultList;
import org.opencms.util.CmsStringUtil;
import org.opencms.xml.A_CmsXmlDocument;
import org.opencms.xml.CmsXmlException;
import org.opencms.xml.CmsXmlUtils;
import org.opencms.xml.content.CmsXmlContentFactory;
import org.opencms.xml.types.CmsXmlDateTimeValue;
import org.opencms.xml.types.I_CmsXmlContentValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;

/**
 * Descrive come mappare il contenuto di un oggetto associato rispetto a un certo locale:
 * <mapping type="dynamic" class="org.opencms.search.fields.CmsRelatedSearchFieldMapping">parametrijson</mapping>
 * {
 *  "forLocale":"it",
 *  "sourceElement":"Manifestazione",
 *  "matchToType":"manifestazione",
 *  "matchToSolrField":"manifestazione",
 *  "matchTargetElement":"Periodo/da"
 *  "matchTargetLocale":"it"
 *  }
 * indica che durante l'indicizzazione del locale 'it' verrà letto l'elemento "matchTargetElement" dal
 * locale "matchTargetLocale" del primo contenuto di tipo "matchToType" a cui è associato quanto presente
 * nell'elemento "sourceElement" attraverso il campo solr "matchToSolrField"
 */
public class CmsRelatedFieldMapping implements I_CmsSearchFieldMapping {

    @SuppressWarnings("javadoc")
    private static final long serialVersionUID = -79901500058147921L;
    /** The log object for this class. */
    private static final Log LOG = CmsLog.getLog(CmsRelatedFieldMapping.class);
    /**
     *
     */

    private String m_param;

    private CmsSearchFieldMappingType m_type;

    private String forLocale;

    private String sourceElement;

    private String matchToType;

    private String matchToSolrField;

    private String matchTargetElement;

    private String matchTargetLocale;

    /**
     * Public constructor for a new search field mapping.
     * <p>
     */
    public CmsRelatedFieldMapping() {

        m_param = null;
        setType(CmsSearchFieldMappingType.DYNAMIC);
    }

    /**
     * Public constructor for a new search field mapping.
     * <p>
     *
     * @param type
     *            the type to use, see
     *            {@link #setType(CmsSearchFieldMappingType)}
     * @param param
     *            the mapping parameter, see {@link #setParam(String)}
     */
    public CmsRelatedFieldMapping(CmsSearchFieldMappingType type, String param) {

        this();
        setParam(param);
        setType(type);

    }

    private void extractValues(CmsObject cms, StringBuffer subcontent, CmsSolrResultList foundMatches)
    throws CmsException, CmsIndexNoContentException, CmsXmlException {

        for (CmsSearchResource searchResource : foundMatches) {

            String resourcePath = cms.getRequestContext().removeSiteRoot(searchResource.getField("path"));
            if (cms.existsResource(resourcePath)) {
                CmsFile file = readFile(cms, cms.readResource(resourcePath));
                A_CmsXmlDocument xmlContent = CmsXmlContentFactory.unmarshal(cms, file);
                I_CmsXmlContentValue value = xmlContent.getValue(
                    normalizeParam(this.matchTargetElement, this.matchTargetLocale),
                    new Locale(this.matchTargetLocale));
                if ((value != null)) {
                    String extracted = null;
                    if (value instanceof CmsXmlDateTimeValue) {
                        extracted = CmsSearchUtil.getDateAsIso8601(((CmsXmlDateTimeValue)value).getDateTimeValue());
                    } else {
                        extracted = value.getPlainText(cms);
                    }
                    if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(extracted)) {
                        subcontent.append(extracted);
                    }
                }
            }
        }
    }

    /**
     * Returns a "\n" separated String of values for the given XPath if
     * according content items can be found.
     * <p>
     *
     * @param contentItems
     *            the content items to get the value from
     * @param xpath
     *            the short XPath parameter to get the value for
     *
     * @return a "\n" separated String of element values found in the content
     *         items for the given XPath
     */
    private String getContentItemForXPath(Map<String, String> contentItems, String xpath) {

        if (contentItems.get(xpath) != null) { // content item found for XPath
            return contentItems.get(xpath);
        } else { // try a multiple value mapping
            StringBuffer result = new StringBuffer();
            for (Map.Entry<String, String> entry : contentItems.entrySet()) {
                if (CmsXmlUtils.removeXpath(entry.getKey()).equals(xpath)) {
                    // the removed path refers an item
                    result.append(entry.getValue());
                    result.append(",");
                }
            }
            return result.length() > 1 ? result.toString().substring(0, result.length() - 1) : null;
        }
    }

    public String getDefaultValue() {

        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Ritorna il primo elemento che corrisponde al path e al tipo ricercati.
     * @param cmsObject l'oggetto cms
     * @param pathToMatch il path ricercato
     * @return la lista dei risultati solr
     * @throws CmsSearchException
     */
    public CmsSolrResultList getMatchingTargets(CmsObject cmsObject, String pathToMatch) throws CmsSearchException {

        Map<String, String[]> query = new HashMap<String, String[]>();
        // filter query
        query.put("defType", new String[] {"edismax"});
        query.put("q", new String[] {"*:*"});
        query.put("rows", new String[] {"1"});
        query.put("start", new String[] {"0"});

        query.put("fq", new String[] {"type:" + this.matchToType, this.matchToSolrField + ":\"" + pathToMatch + "\""});

        CmsSolrQuery solrquery = new CmsSolrQuery(cmsObject, query);
        List<Locale> locales = new ArrayList<Locale>();
        locales.add(new Locale(this.matchTargetLocale));

        solrquery.setLocales(locales);
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "[" + cmsObject.getRequestContext().getCurrentUser().getName() + "] Query:" + solrquery.toString());
        }

        return OpenCms.getSearchManager().getIndexSolr(CmsSolrIndex.DEFAULT_INDEX_NAME_OFFLINE).search(
            cmsObject,
            solrquery,
            true);

    }

    @Override
    public String getParam() {

        return m_param;
    }

    @Override
    public String getStringValue(
        CmsObject cms,
        CmsResource res,
        I_CmsExtractionResult extractionResult,
        List<CmsProperty> properties,
        List<CmsProperty> propertiesSearched) {

        String content = null;
        StringBuffer subcontent = new StringBuffer();
        if ((null != extractionResult) && CmsStringUtil.isNotEmptyOrWhitespaceOnly(getParam())) {
            try {
                JSONObject config = new JSONObject(getParam());

                this.forLocale = config.getString("forLocale");
                this.sourceElement = config.getString("sourceElement");
                this.matchToType = config.getString("matchToType");
                this.matchToSolrField = config.getString("matchToSolrField");
                this.matchTargetElement = config.getString("matchTargetElement");
                this.matchTargetLocale = config.getString("matchTargetLocale");
            } catch (Exception e) {
                LOG.error("Errore inizializzazione configurazione mapping solr", e);

            }
            //path risorsa da leggere
            Map<String, String> localizedContentItems = null;
            String xpath = null;
            if (this.forLocale.length() > 1) {
                OpenCms.getLocaleManager();
                localizedContentItems = extractionResult.getContentItems(
                    CmsLocaleManager.getLocale(this.forLocale.trim()));
                xpath = this.sourceElement.trim();
            } else {
                localizedContentItems = extractionResult.getContentItems();
                xpath = this.sourceElement.trim();
            }
            content = getContentItemForXPath(localizedContentItems, xpath);

            if (content != null) {
                String[] fileParts = content.split(",");
                //se è stato trovato un contenuto nell'elemento in configurazione
                //si effettua una ricerca solr per trovare corrispondenze
                for (int i = 0; i < fileParts.length; i++) {
                    try {
                        CmsSolrResultList foundMatches = getMatchingTargets(cms, fileParts[i]);
                        extractValues(cms, subcontent, foundMatches);
                    } catch (CmsException e1) {
                        LOG.error("Errore ricerca match correlazioni", e1);
                    }

                }
            }
        }
        return subcontent.toString();
    }

    @Override
    public CmsSearchFieldMappingType getType() {

        return m_type;
    }

    private String normalizeParam(String param, String locale) {

        String p = param;
        if (!p.endsWith("]")) {
            p += "[1]";
        }
        return p;
    }

    /**
     * Upgrades the given resource to a {@link CmsFile} with content.<p>
     *
     * @param cms the current users OpenCms context
     * @param resource the resource to upgrade
     *
     * @return the given resource upgraded to a {@link CmsFile} with content
     *
     * @throws CmsException if the resource could not be read
     * @throws CmsIndexNoContentException if the resource has no content
     */
    protected CmsFile readFile(CmsObject cms, CmsResource resource) throws CmsException, CmsIndexNoContentException {

        CmsFile file = cms.readFile(resource);
        if (file.getLength() <= 0) {
            throw new CmsIndexNoContentException(
                Messages.get().container(Messages.ERR_NO_CONTENT_1, resource.getRootPath()));
        }
        return file;
    }

    @Override
    public void setDefaultValue(String defaultValue) {

        // Should not be setable
    }

    @Override
    public void setParam(String param) {

        m_param = param;

    }

    @Override
    public void setType(CmsSearchFieldMappingType type) {

        m_type = type;

    }

    @Override
    public void setType(String type) {

        m_type = CmsSearchFieldMappingType.valueOf(type);

    }

}