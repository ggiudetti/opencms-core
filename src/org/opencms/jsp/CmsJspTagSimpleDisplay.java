/*
 * This library is part of OpenCms -
 * the Open Source Content Management System
 *
 * Copyright (c) Alkacon Software GmbH & Co. KG (http://www.alkacon.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * For further information about Alkacon Software, please see the
 * company website: http://www.alkacon.com
 *
 * For further information about OpenCms, please see the
 * project website: http://www.opencms.org
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.opencms.jsp;

import org.opencms.ade.configuration.formatters.CmsFormatterConfigurationCacheState;
import org.opencms.file.CmsObject;
import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.file.collectors.I_CmsCollectorPostCreateHandler;
import org.opencms.flex.CmsFlexController;
import org.opencms.jsp.util.CmsJspStandardContextBean;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.util.CmsUUID;
import org.opencms.xml.containerpage.CmsContainerElementBean;
import org.opencms.xml.containerpage.I_CmsFormatterBean;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.jsp.tagext.BodyTagSupport;

import org.apache.commons.logging.Log;

/**
 * The 'simpledisplay' tag can be used to display a single resource using a formatter. It also allows to activate direct editing.<p>
 * It is less flexible but simpler to use than the 'display' tag in that it only allows you to specify a single, fixed formatter configuration as an attribute,
 * rather than a set of type-dependent formatters with the displayformatter tag.
 *
 */
public class CmsJspTagSimpleDisplay extends BodyTagSupport implements I_CmsJspTagParamParent {

    /** The log object for this class. */
    private static final Log LOG = CmsLog.getLog(CmsJspTagSimpleDisplay.class);

    /** The serial version id. */
    private static final long serialVersionUID = 2285680951218629093L;


    /** Flag, indicating if the contents should be cacheable. */
    private boolean m_cacheable;
    
    /** Flag, indicating if the create option should be displayed. */
    private boolean m_canCreate;

    /** Flag, indicating if the delete option should be displayed. */
    private boolean m_canDelete;

    /** The tag attribute's value, specifying the path to the (sub)sitemap where new content should be created. */
    private String m_creationSiteMap;

    /** The editable flag. */
    private boolean m_editable;

    /** The settings parameter map. */
    private Map<String, String> m_parameterMap;

    /** The pass settings flag. */
    private boolean m_passSettings;

    /** The fully qualified class name of the post create handler to use. */
    private String m_postCreateHandler;

    /** The element settings to be used. */
    private Map<String, String> m_settings;

    /** The site path to the resource to display. */
    private String m_value;

    /** Stores the formatter path. */
    private String m_formatterPath;

    /**
     * Constructor.<p>
     */
    public CmsJspTagSimpleDisplay() {

        m_parameterMap = new LinkedHashMap<String, String>();
    }

    /**
     * @see org.opencms.jsp.I_CmsJspTagParamParent#addParameter(java.lang.String, java.lang.String)
     */
    public void addParameter(String name, String value) {

        // No null values allowed in parameters
        if ((name == null) || (value == null)) {
            return;
        }

        m_parameterMap.put(name, value);
    }

    /**
     * @see javax.servlet.jsp.tagext.BodyTagSupport#doEndTag()
     */
    @Override
    public int doEndTag() {

        ServletRequest request = pageContext.getRequest();
        ServletResponse response = pageContext.getResponse();
        if (CmsFlexController.isCmsRequest(request)) {
            // this will always be true if the page is called through OpenCms
            CmsObject cms = CmsFlexController.getCmsObject(request);
            try {
                boolean isOnline = cms.getRequestContext().getCurrentProject().isOnlineProject();
                CmsResource res = null;
                if (CmsUUID.isValidUUID(m_value)) {
                    CmsUUID structureId = new CmsUUID(m_value);
                    res = isOnline
                    ? cms.readResource(structureId)
                    : cms.readResource(structureId, CmsResourceFilter.IGNORE_EXPIRATION);
                } else {
                    res = isOnline
                    ? cms.readResource(m_value)
                    : cms.readResource(m_value, CmsResourceFilter.IGNORE_EXPIRATION);
                }
                CmsResource formatterResource = cms.readResource(m_formatterPath);
                CmsFormatterConfigurationCacheState formatterCache = OpenCms.getADEManager().getCachedFormatters(
                    cms.getRequestContext().getCurrentProject().isOnlineProject());
                I_CmsFormatterBean formatter = formatterCache.getFormatters().get(formatterResource.getStructureId());
                Map<String, String> settings = new HashMap<String, String>();
                String formatterId = formatter.getId();
                int prefixLength = formatterId.length() + 1;
                for (Entry<String, String> entry : m_parameterMap.entrySet()) {
                    if (entry.getKey().startsWith(formatterId)) {
                        settings.put(entry.getKey().substring(prefixLength), entry.getValue());
                    } else if (!settings.containsKey(entry.getKey())) {
                        settings.put(entry.getKey(), entry.getValue());
                    }
                }

                CmsJspTagDisplay.displayAction(
                    res,
                    formatter,
                    settings,
                    m_editable,
                    m_canCreate,
                    m_canDelete,
                    m_cacheable,
                    m_creationSiteMap,
                    m_postCreateHandler,
                    pageContext,
                    request,
                    response);
            } catch (CmsException e) {
                LOG.error(e.getLocalizedMessage(), e);
            }
        }
        release();
        return EVAL_PAGE;
    }

    /**
     * @see javax.servlet.jsp.tagext.BodyTagSupport#doStartTag()
     */
    @Override
    public int doStartTag() {

        if (Boolean.valueOf(m_passSettings).booleanValue()) {
            CmsContainerElementBean element = CmsJspStandardContextBean.getInstance(
                pageContext.getRequest()).getElement();
            if (element != null) {
                m_parameterMap.putAll(element.getSettings());
            }
        }
        if (m_settings != null) {
            m_parameterMap.putAll(m_settings);
        }

        return EVAL_BODY_BUFFERED;
    }

    /**
     * Returns the cacheable.<p>
     *
     * @return the cacheable
     */
    public boolean getCacheble() {

         return m_cacheable;
    }
    
    /**
     * Returns the editable.<p>
     *
     * @return the editable
     */
    public boolean getEditable() {

        return m_editable;
    }

    /**
     * Returns the passSettings.<p>
     *
     * @return the passSettings
     */
    public boolean getPassSettings() {

        return m_passSettings;
    }

    /**
     * Returns the element settings to be used.<p>
     *
     * @return the element settings to be used
     */
    public Map<String, String> getSettings() {

        return m_settings;
    }

    /**
     * Returns the value.<p>
     *
     * @return the value
     */
    public String getValue() {

        return m_value;
    }

    /**
     * @see javax.servlet.jsp.tagext.BodyTagSupport#release()
     */
    @Override
    public void release() {

        super.release();
        m_parameterMap.clear();
        m_settings = null;
        m_passSettings = false;
        m_editable = false;
        m_value = null;
    }

    /** Setter for the "cacheable" attribute of the tag.
     * @param cacheable value of the tag's attribute "cacheable".
     */
    public void setcacheable(boolean cacheable) {

         m_cacheable = cacheable;
    }

    /** Setter for the "cacheable" attribute of the tag.
     * @param cacheable value of the tag's attribute "cacheable".
     */
    public void setcacheable(String cacheable) {

         m_cacheable = Boolean.valueOf(cacheable).booleanValue();
    }
    
    /** Setter for the "create" attribute of the tag.
     * @param canCreate value of the tag's attribute "create".
     */
    public void setCreate(boolean canCreate) {

        m_canCreate = canCreate;
    }

    /** Setter for the "create" attribute of the tag.
     * @param canCreate value of the tag's attribute "create".
     */
    public void setCreate(String canCreate) {

        m_canCreate = Boolean.valueOf(canCreate).booleanValue();
    }

    /** Setter for the "creationSiteMap" attribute of the tag.
     * @param sitePath value of the "creationSiteMap" attribute of the tag.
     */
    public void setCreationSiteMap(String sitePath) {

        m_creationSiteMap = sitePath;
    }

    /**Setter for the "delete" attribute of the tag.
     * @param canDelete value of the "delete" attribute of the tag.
     */
    public void setDelete(boolean canDelete) {

        m_canDelete = canDelete;
    }

    /**Setter for the "delete" attribute of the tag.
     * @param canDelete value of the "delete" attribute of the tag.
     */
    public void setDelete(String canDelete) {

        m_canDelete = Boolean.valueOf(canDelete).booleanValue();
    }

    /**
     * Sets the editable.<p>
     *
     * @param editable the editable to set
     */
    public void setEditable(boolean editable) {

        m_editable = editable;
    }

    /**
     * Sets the editable.<p>
     *
     * @param editable the editable to set
     */
    public void setEditable(String editable) {

        m_editable = Boolean.valueOf(editable).booleanValue();
    }

    /**
     * Sets the formatter path.<p>
     *
     * @param formatter the formatter path
     */
    public void setFormatter(String formatter) {

        m_formatterPath = formatter;
    }

    /**
     * Sets the passSettings.<p>
     *
     * @param passSettings the passSettings to set
     */
    public void setPassSettings(Boolean passSettings) {

        m_passSettings = passSettings.booleanValue();
    }

    /** Setter for the "postCreateHandler" attribute of the tag.
     * @param postCreateHandler fully qualified class name of the {@link I_CmsCollectorPostCreateHandler} to use.
     */
    public void setPostCreateHandler(final String postCreateHandler) {

        m_postCreateHandler = postCreateHandler;
    }

    /**
     * Sets the element settings to be used.<p>
     *
     * @param settings the element settings to be used
     */
    public void setSettings(Map<String, String> settings) {

        m_settings = settings;
    }

    /**
     * Sets the value.<p>
     *
     * @param value the value to set
     */
    public void setValue(String value) {

        m_value = value;
    }

}
