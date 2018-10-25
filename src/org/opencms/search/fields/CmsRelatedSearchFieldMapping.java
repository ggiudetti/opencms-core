
package org.opencms.search.fields;

import org.opencms.file.CmsFile;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsProperty;
import org.opencms.file.CmsResource;
import org.opencms.i18n.CmsLocaleManager;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.search.CmsIndexException;
import org.opencms.search.documents.CmsIndexNoContentException;
import org.opencms.search.documents.Messages;
import org.opencms.search.extractors.CmsExtractionResult;
import org.opencms.search.extractors.I_CmsExtractionResult;
import org.opencms.util.CmsStringUtil;
import org.opencms.xml.A_CmsXmlDocument;
import org.opencms.xml.CmsXmlUtils;
import org.opencms.xml.content.CmsXmlContentFactory;
import org.opencms.xml.content.I_CmsXmlContentHandler;
import org.opencms.xml.types.I_CmsXmlContentValue;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;

/**
 * Descrive come mappare il contenuto di un oggetto associato rispetto a un certo locale:
 * <mapping type="dynamic" class="org.opencms.search.fields.CmsRelatedSearchFieldMapping">it|Localita,it|Titolo</mapping>
 * indica che durante l'indicizzazione del locale 'it' verrà letto il campo Titolo dal locale 'it' del contenuto associato
 * nel campo 'Localita'
 *
 */
public class CmsRelatedSearchFieldMapping implements I_CmsSearchFieldMapping {

    @SuppressWarnings("javadoc")
    private static final long serialVersionUID = -79901500058147921L;
    /** The log object for this class. */
    private static final Log LOG = CmsLog.getLog(CmsRelatedSearchFieldMapping.class);
    /**
     *
     */

    private String m_param;

    private CmsSearchFieldMappingType m_type;

    /**
     * Public constructor for a new search field mapping.
     * <p>
     */
    public CmsRelatedSearchFieldMapping() {

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
    public CmsRelatedSearchFieldMapping(CmsSearchFieldMappingType type, String param) {

        this();
        setParam(param);
        setType(type);
    }

    public I_CmsExtractionResult extractContent(CmsObject cms, CmsResource resource, Locale locale)
    throws CmsException {

        try {
            CmsFile file = readFile(cms, resource);
            A_CmsXmlDocument xmlContent = CmsXmlContentFactory.unmarshal(cms, file);
            I_CmsXmlContentHandler handler = xmlContent.getHandler();

            List<String> elements = xmlContent.getNames(locale);
            StringBuffer content = new StringBuffer();
            LinkedHashMap<String, String> items = new LinkedHashMap<String, String>();
            for (Iterator<String> i = elements.iterator(); i.hasNext();) {
                String xpath = i.next();
                // xpath will have the form "Text[1]" or "Nested[1]/Text[1]"
                I_CmsXmlContentValue value = xmlContent.getValue(xpath, locale);
                if (handler.isSearchable(value)) {
                    // the content value is searchable
                    String extracted = value.getPlainText(cms);
                    if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(extracted)) {
                        items.put(xpath, extracted);
                        content.append(extracted);
                        content.append('\n');
                    }
                }
            }
            return new CmsExtractionResult(content.toString(), items);
        } catch (Exception e) {
            throw new CmsIndexException(
                Messages.get().container(Messages.ERR_TEXT_EXTRACTION_1, resource.getRootPath()),
                e);
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
                if (CmsXmlUtils.removeXpath(entry.getKey()).equals(xpath)) { // the
                    // removed
                    // path
                    // refers
                    // an
                    // item
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
        if ((extractionResult != null) && CmsStringUtil.isNotEmptyOrWhitespaceOnly(getParam())) {

            //path risorsa da leggere
            String[] paramsParts = getParam().split(",");
            String[] paramParts = paramsParts[0].split("\\|");
            Map<String, String> localizedContentItems = null;
            String xpath = null;
            if (paramParts.length > 1) {
                OpenCms.getLocaleManager();
                localizedContentItems = extractionResult.getContentItems(
                    CmsLocaleManager.getLocale(paramParts[0].trim()));
                xpath = paramParts[1].trim();
            } else {
                localizedContentItems = extractionResult.getContentItems();
                xpath = paramParts[0].trim();
            }
            content = getContentItemForXPath(localizedContentItems, xpath);

            if (content != null) {
                String[] fileParts = content.split(",");
                for (int i = 0; i < fileParts.length; i++) {
                    if (cms.existsResource(fileParts[i])) {
                        try {

                            String[] subParamParts = paramsParts[1].split("\\|");
                            CmsFile file = readFile(cms, cms.readResource(fileParts[i]));
                            A_CmsXmlDocument xmlContent = CmsXmlContentFactory.unmarshal(cms, file);
                            I_CmsXmlContentHandler handler = xmlContent.getHandler();
                            I_CmsXmlContentValue value = xmlContent.getValue(
                                normalizeParam(subParamParts[1], subParamParts[0]),
                                new Locale(subParamParts[0]));
                            if (handler.isSearchable(value)) {
                                // the content value is searchable
                                String extracted = value.getPlainText(cms);
                                if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(extracted)) {

                                    subcontent.append(extracted);
                                    subcontent.append('\n');
                                }
                            }

                        } catch (CmsException e) {
                            LOG.error(e);
                        }
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
