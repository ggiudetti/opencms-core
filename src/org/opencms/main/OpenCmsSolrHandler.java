/*
* File   : $Source$
* Date   : $Date$
* Version: $Revision$
*
* This library is part of OpenCms -
* the Open Source Content Management System
*
* Copyright (C) 2002 - 2009 Alkacon Software (http://www.alkacon.com)
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

package org.opencms.main;

import org.opencms.file.CmsObject;
import org.opencms.search.CmsSearchException;
import org.opencms.search.CmsSearchManager;
import org.opencms.search.solr.CmsSolrIndex;
import org.opencms.search.solr.CmsSolrQuery;
import org.opencms.site.CmsSite;
import org.opencms.util.CmsRequestUtil;
import org.opencms.util.CmsStringUtil;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.solr.common.params.CommonParams;

/**
 * The OpenCms Solr handler.<p>
 *
 * Reachable under: "/opencms/opencms/handleSolrSelect".<p>
 *
 * Usage example:<p>
 * <code>http://localhost:8080/opencms/opencms/handleSolrSelect?fq=parent-folders:/sites/+type=v8article&fl=path&rows=10&sort=path%20asc</code>
 *
 * @since 8.5.0
 */
public class OpenCmsSolrHandler extends HttpServlet implements I_CmsRequestHandler {

    /**
     * Encapsulate each request with an inner class in order to make OpenCmsSolrHander thread-safe.
     */
    class Context {

        /** The CMS object. */
        public CmsObject m_cms;

        /** The Solr index. */
        public CmsSolrIndex m_index;

        /** The request parameters. */
        public Map<String, String[]> m_params;

        /** The Solr query. */
        public CmsSolrQuery m_query;
    }

    /**
     * An enum storing the handler names implemented by this class.<p>
     */
    private static enum HANDLER_NAMES {

        /**
         * A constant for the '/select' request handler of the embedded Solr server.
         * This handler is reachable under "/opencms/opencms/handleSolrSelect".<p>
         */
        SolrSelect,

        /**
         * A constant for the '/spell' request handler of the embedded Solr server.
         * This handler is reachable under "/opencms/opencms/handleSolrSpell".<p>
         */
        SolrSpell,
        /**
         * A constant for the '/suggest' request handler of the embedded Solr server.
         * This handler is reachable under "/opencms/opencms/handleSolrSuggest".<p>
         */
        SolrSuggest
    }

    /** A constant for the optional 'baseUri' parameter. */
    public static final String PARAM_BASE_URI = "baseUri";

    /** A constant for the optional 'core' parameter. */
    public static final String PARAM_CORE = "core";

    /** A constant for the optional 'index' parameter. */
    public static final String PARAM_INDEX = "index";

    /** A constant for the HTTP 'referer'. */
    protected static final String HEADER_REFERER_KEY = "referer";

    /** The UID. */
    private static final long serialVersionUID = 2460644631508735724L;

    /**
     * OpenCms servlet main request handling method.<p>
     *
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {

        handle(req, res, HANDLER_NAMES.SolrSelect.toString());
    }

    /**
     * OpenCms servlet POST request handling method,
     * will just call {@link #doGet(HttpServletRequest, HttpServletResponse)}.<p>
     *
     * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {

        doGet(req, res);
    }

    /**
     * Returns the base URI.<p>
     *
     * @param req the servlet request
     * @param cms the CmsObject
     *
     * @return the base URI
     */
    private String getBaseUri(HttpServletRequest req, CmsObject cms) {

        String baseUri = req.getParameter(PARAM_BASE_URI);
        if (CmsStringUtil.isEmptyOrWhitespaceOnly(baseUri)) {
            String referer = req.getHeader(HEADER_REFERER_KEY);
            CmsSite site = OpenCms.getSiteManager().getSiteForSiteRoot(cms.getRequestContext().getSiteRoot());
            if (site != null) {
                String prefix = site.getServerPrefix(cms, "/") + OpenCms.getStaticExportManager().getVfsPrefix();
                if ((referer != null) && referer.startsWith(prefix)) {
                    baseUri = referer.substring(prefix.length());
                }
            }
        }
        return baseUri;
    }

    /**
     * Returns the CMS object.<p>
     *
     * @param req the request
     *
     * @return the CMS object
     *
     * @throws CmsException if something goes wrong
     */
    protected CmsObject getCmsObject(HttpServletRequest req) throws CmsException {

        CmsObject cms = OpenCmsCore.getInstance().initCmsObjectFromSession(req);
        // use the guest user as fall back
        if (cms == null) {
            cms = OpenCmsCore.getInstance().initCmsObject(OpenCms.getDefaultUsers().getUserGuest());
            String siteRoot = OpenCmsCore.getInstance().getSiteManager().matchRequest(req).getSiteRoot();
            cms.getRequestContext().setSiteRoot(siteRoot);
        }
        String baseUri = getBaseUri(req, cms);
        if (baseUri != null) {
            cms.getRequestContext().setUri(baseUri);
        }
        return cms;
    }

    /**
     * @see org.opencms.main.I_CmsRequestHandler#getHandlerNames()
     */
    public String[] getHandlerNames() {

        return CmsStringUtil.enumNameToStringArray(HANDLER_NAMES.values());
    }

    /**
     * @see org.opencms.main.I_CmsRequestHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String)
     */
    public void handle(HttpServletRequest req, HttpServletResponse res, String name) throws IOException {

        final HANDLER_NAMES handlerName = HANDLER_NAMES.valueOf(name);
        if (handlerName != null) {
            try {
                Context context = initializeRequest(req, res);
                if ((context.m_params.get(CommonParams.Q) != null) || (context.m_params.get(CommonParams.FQ) != null)) {
                    switch (handlerName) {
                        case SolrSelect:
                            context.m_index.select(res, context.m_cms, context.m_query, true);
                            break;
                        case SolrSpell:
                            context.m_index.spellCheck(res, context.m_cms, context.m_query);
                            break;
                        case SolrSuggest:
                            context.m_index.suggest(res, context.m_cms, context.m_query);
                            break;
                        default:
                            break;
                    }
                }
            } catch (Exception e) {
                res.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
                String message = Messages.get().getBundle().key(Messages.GUI_SOLR_UNEXPECTED_ERROR_0);
                String formattedException = CmsException.getStackTraceAsString(e).replace("\n", "<br/>");
                res.getWriter().println(
                    Messages.get().getBundle().key(Messages.GUI_SOLR_ERROR_HTML_1, message + formattedException));
            }
        }
    }

    /**
     * Initialized the search request and sets the local parameter.<p>
     *
     * @param req the servlet request
     * @param res the servlet response
     *
     * @return the generated context
     *
     * @throws CmsException if something goes wrong
     * @throws Exception if something goes wrong
     * @throws CmsSearchException if something goes wrong
     * @throws IOException if something goes wrong
     */
    protected Context initializeRequest(HttpServletRequest req, HttpServletResponse res)
    throws CmsException, Exception, CmsSearchException, IOException {

        Context context = new Context();
        context.m_cms = getCmsObject(req);
        context.m_params = CmsRequestUtil.createParameterMap(req.getParameterMap());
        context.m_index = CmsSearchManager.getIndexSolr(context.m_cms, context.m_params);

        if (context.m_index != null) {
            context.m_query = new CmsSolrQuery(context.m_cms, context.m_params);
        } else {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            String indexName = context.m_params.get(PARAM_CORE) != null
            ? context.m_params.get(PARAM_CORE)[0]
            : (context.m_params.get(PARAM_INDEX) != null ? context.m_params.get(PARAM_INDEX)[0] : null);
            String message = Messages.get().getBundle().key(Messages.GUI_SOLR_INDEX_NOT_FOUND_1, indexName);
            res.getWriter().println(Messages.get().getBundle().key(Messages.GUI_SOLR_ERROR_HTML_1, message));
        }

        return context;
    }
}
