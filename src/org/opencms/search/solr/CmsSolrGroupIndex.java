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

package org.opencms.search.solr;

import org.opencms.file.CmsObject;
import org.opencms.file.CmsProject;
import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.i18n.CmsEncoder;
import org.opencms.main.CmsIllegalArgumentException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.search.CmsSearchException;
import org.opencms.search.CmsSearchResource;
import org.opencms.search.fields.CmsSearchField;
import org.opencms.security.CmsRole;
import org.opencms.security.CmsRoleViolationException;
import org.opencms.util.CmsStringUtil;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletResponse;

import org.apache.commons.logging.Log;
import org.apache.lucene.index.Term;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.response.Group;
import org.apache.solr.client.solrj.response.GroupCommand;
import org.apache.solr.client.solrj.response.GroupResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.BinaryQueryResponseWriter;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocListAndSet;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.QParser;
import org.apache.solr.util.FastWriter;

/**
 * Implements the search within an Solr index.<p>
 *
 * @since 8.5.0
 */
public class CmsSolrGroupIndex extends CmsSolrIndex {

    /** The name for the parameters key of the response header. */
    private static final String HEADER_PARAMS_NAME = "params";

    /** The log object for this class. */
    private static final Log LOG = CmsLog.getLog(CmsSolrIndex.class);

    /** Pseudo resource used for not permission checked indexes. */
    private static final CmsResource PSEUDO_RES = new CmsResource(
        null,
        null,
        null,
        0,
        false,
        0,
        null,
        null,
        0L,
        null,
        0L,
        null,
        0L,
        0L,
        0,
        0,
        0L,
        0);

    /** The name of the key that is used for the result documents inside the Solr query response. */
    private static final String QUERY_RESPONSE_NAME = "response";

    /** The name of the key that is used for the query time. */
    private static final String QUERY_TIME_NAME = "QTime";

    /** A constant for UTF-8 charset. */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** The post document manipulator. */
    private I_CmsSolrPostSearchProcessor m_postProcessor;

    /**
     * Default constructor.<p>
     */
    public CmsSolrGroupIndex() {

        super();
    }

    /**
     * Public constructor to create a Solr index.<p>
     *
     * @param name the name for this index.<p>
     *
     * @throws CmsIllegalArgumentException if something goes wrong
     */
    public CmsSolrGroupIndex(String name)
    throws CmsIllegalArgumentException {

        super(name);
    }

    /**
     * Checks if the current user is allowed to access non-online indexes.<p>
     *
     * To access non-online indexes the current user must be a workplace user at least.<p>
     *
     * @param cms the CMS object initialized with the current request context / user
     *
     * @throws CmsSearchException thrown if the access is not permitted
     */
    private void checkOfflineAccess(CmsObject cms) throws CmsSearchException {

        // If an offline index is being selected, check permissions
        if (!CmsProject.ONLINE_PROJECT_NAME.equals(getProject())) {
            // only if the user has the role Workplace user, he is allowed to access the Offline index
            try {
                OpenCms.getRoleManager().checkRole(cms, CmsRole.ELEMENT_AUTHOR);
            } catch (CmsRoleViolationException e) {
                throw new CmsSearchException(
                    Messages.get().container(
                        Messages.LOG_SOLR_ERR_SEARCH_PERMISSION_VIOLATION_2,
                        getName(),
                        cms.getRequestContext().getCurrentUser()),
                    e);
            }
        }
    }

    /**
     * Performs the actual search.<p>
     *
     * @param cms the current OpenCms context
     * @param ignoreMaxRows <code>true</code> to return all all requested rows, <code>false</code> to use max rows
     * @param query the OpenCms Solr query
     * @param response the servlet response to write the query result to, may also be <code>null</code>
     * @param ignoreSearchExclude if set to false, only contents with search_exclude unset or "false" will be found - typical for the the non-gallery case
     * @param filter the resource filter to use
     *
     * @return the found documents
     *
     * @throws CmsSearchException if something goes wrong
     *
     * @see #search(CmsObject, CmsSolrQuery, boolean)
     */
    @Override
    @SuppressWarnings("unchecked")
    public CmsSolrResultList search(
        CmsObject cms,
        final CmsSolrQuery query,
        boolean ignoreMaxRows,
        ServletResponse response,
        boolean ignoreSearchExclude,
        CmsResourceFilter filter)
    throws CmsSearchException {

        // check if the user is allowed to access this index
        checkOfflineAccess(cms);
        if (!ignoreSearchExclude) {
            query.addFilterQuery(CmsSearchField.FIELD_SEARCH_EXCLUDE + ":\"false\"");
        }

        int previousPriority = Thread.currentThread().getPriority();
        long startTime = System.currentTimeMillis();

        // remember the initial query
        SolrQuery initQuery = query.clone();

        query.setHighlight(false);
        LocalSolrQueryRequest solrQueryRequest = null;
        try {

            // initialize the search context
            CmsObject searchCms = OpenCms.initCmsObject(cms);

            // change thread priority in order to reduce search impact on overall system performance
            if (getPriority() > 0) {
                Thread.currentThread().setPriority(getPriority());
            }

            // the lists storing the found documents that will be returned
            List<CmsSearchResource> resourceDocumentList = new ArrayList<CmsSearchResource>();
            SolrDocumentList solrDocumentList = new SolrDocumentList();

            // Initialize rows, offset, end and the current page.
            int rows = query.getRows() != null ? query.getRows().intValue() : CmsSolrQuery.DEFAULT_ROWS.intValue();
            if (!ignoreMaxRows && (rows > ROWS_MAX)) {
                rows = ROWS_MAX;
            }
            int start = query.getStart() != null ? query.getStart().intValue() : 0;
            int end = start + rows;
            int page = 0;
            if (rows > 0) {
                page = Math.round(start / rows) + 1;
            }

            // set the start to '0' and expand the rows before performing the query
            query.setStart(new Integer(0));
            query.setRows(new Integer((5 * rows * page) + start));

            // perform the Solr query and remember the original Solr response
            QueryResponse queryResponse = m_solr.query(query);
            long solrTime = System.currentTimeMillis() - startTime;

            long hitCount = 0L;
            List<SolrDocumentList> solrDocs = new ArrayList<SolrDocumentList>();
            //check for group commands
            GroupResponse groups = queryResponse.getGroupResponse();
            if (groups != null) {
                List<GroupCommand> grpVals = groups.getValues();
                for (GroupCommand cmd : grpVals) {
                    String name = cmd.getName();
                    hitCount += cmd.getMatches();
                    List<Group> cmdVals = cmd.getValues();
                    for (Group gVal : cmdVals) {
                        String val = gVal.getGroupValue();
                        SolrDocumentList grpRes = gVal.getResult();
                        long valCount = grpRes.getNumFound();
                        solrDocs.add(grpRes);
                    }
                }
            } else {
                // initialize the counts
                hitCount = queryResponse.getResults().getNumFound();
                solrDocs.add(queryResponse.getResults());
            }

            start = -1;
            end = -1;
            if ((rows > 0) && (page > 0) && (hitCount > 0)) {
                // calculate the final size of the search result
                start = rows * (page - 1);
                end = start + rows;
                // ensure that both i and n are inside the range of foundDocuments.size()
                start = new Long((start > hitCount) ? hitCount : start).intValue();
                end = new Long((end > hitCount) ? hitCount : end).intValue();
            } else {
                // return all found documents in the search result
                start = 0;
                end = new Long(hitCount).intValue();
            }
            long visibleHitCount = hitCount;
            float maxScore = 0;

            // If we're using a postprocessor, (re-)initialize it before using it
            if (m_postProcessor != null) {
                m_postProcessor.init();
            }

            // process found documents
            List<CmsSearchResource> allDocs = new ArrayList<CmsSearchResource>();
            int cnt = 0;
            for (SolrDocumentList results : solrDocs) {
                for (int i = 0; (i < results.size()) && (cnt < end); i++) {
                    try {
                        SolrDocument doc = results.get(i);
                        CmsSolrDocument searchDoc = new CmsSolrDocument(doc);
                        if (needsPermissionCheck(searchDoc)) {
                            // only if the document is an OpenCms internal resource perform the permission check
                            CmsResource resource = filter == null
                            ? getResource(searchCms, searchDoc)
                            : getResource(searchCms, searchDoc, filter);
                            if (resource != null) {
                                // permission check performed successfully: the user has read permissions!
                                if (cnt >= start) {
                                    if (m_postProcessor != null) {
                                        doc = m_postProcessor.process(
                                            searchCms,
                                            resource,
                                            (SolrInputDocument)searchDoc.getDocument());
                                    }
                                    resourceDocumentList.add(new CmsSearchResource(resource, searchDoc));
                                    if (null != doc) {
                                        solrDocumentList.add(doc);
                                    }
                                    maxScore = maxScore < searchDoc.getScore() ? searchDoc.getScore() : maxScore;
                                }
                                allDocs.add(new CmsSearchResource(resource, searchDoc));
                                cnt++;
                            } else {
                                visibleHitCount--;
                            }
                        } else {
                            // if permission check is not required for this index,
                            // add a pseudo resource together with document to the results
                            resourceDocumentList.add(new CmsSearchResource(PSEUDO_RES, searchDoc));
                            solrDocumentList.add(doc);
                            maxScore = maxScore < searchDoc.getScore() ? searchDoc.getScore() : maxScore;
                            cnt++;
                        }
                    } catch (Exception e) {
                        // should not happen, but if it does we want to go on with the next result nevertheless
                        LOG.warn(Messages.get().getBundle().key(Messages.LOG_SOLR_ERR_RESULT_ITERATION_FAILED_0), e);
                    }
                }
            }
            // the last documents were all secret so let's take the last found docs
            if (resourceDocumentList.isEmpty() && (allDocs.size() > 0)) {
                page = Math.round(allDocs.size() / rows) + 1;
                int showCount = allDocs.size() % rows;
                showCount = showCount == 0 ? rows : showCount;
                start = allDocs.size() - new Long(showCount).intValue();
                end = allDocs.size();
                if (allDocs.size() > start) {
                    resourceDocumentList = allDocs.subList(start, end);
                    for (CmsSearchResource r : resourceDocumentList) {
                        maxScore = maxScore < r.getDocument().getScore() ? r.getDocument().getScore() : maxScore;
                        solrDocumentList.add(((CmsSolrDocument)r.getDocument()).getSolrDocument());
                    }
                }
            }
            long processTime = System.currentTimeMillis() - startTime - solrTime;

            // create and return the result
            solrDocumentList.setStart(start);
            solrDocumentList.setMaxScore(new Float(maxScore));
            solrDocumentList.setNumFound(visibleHitCount);

            if (groups == null) {
                queryResponse.getResponse().setVal(
                    queryResponse.getResponse().indexOf(QUERY_RESPONSE_NAME, 0),
                    solrDocumentList);

                queryResponse.getResponseHeader().setVal(
                    queryResponse.getResponseHeader().indexOf(QUERY_TIME_NAME, 0),
                    new Integer(new Long(System.currentTimeMillis() - startTime).intValue()));
            }
            long highlightEndTime = System.currentTimeMillis();
            SolrCore core = m_solr instanceof EmbeddedSolrServer
            ? ((EmbeddedSolrServer)m_solr).getCoreContainer().getCore(getCoreName())
            : null;
            CmsSolrResultList result = null;
            try {
                SearchComponent highlightComponenet = null;
                if (core != null) {
                    highlightComponenet = core.getSearchComponent("highlight");
                    solrQueryRequest = new LocalSolrQueryRequest(core, queryResponse.getResponseHeader());
                }
                SolrQueryResponse solrQueryResponse = null;
                if (solrQueryRequest != null) {
                    // create and initialize the solr response
                    solrQueryResponse = new SolrQueryResponse();
                    solrQueryResponse.setAllValues(queryResponse.getResponse());
                    int paramsIndex = queryResponse.getResponseHeader().indexOf(HEADER_PARAMS_NAME, 0);
                    NamedList<Object> header = null;
                    Object o = queryResponse.getResponseHeader().getVal(paramsIndex);
                    if (o instanceof NamedList) {
                        header = (NamedList<Object>)o;
                        header.setVal(header.indexOf(CommonParams.ROWS, 0), new Integer(rows));
                        header.setVal(header.indexOf(CommonParams.START, 0), new Long(start));
                    }

                    // set the OpenCms Solr query as parameters to the request
                    solrQueryRequest.setParams(initQuery);

                    // perform the highlighting
                    if ((header != null) && (initQuery.getHighlight()) && (highlightComponenet != null)) {
                        header.add(HighlightParams.HIGHLIGHT, "on");
                        if ((initQuery.getHighlightFields() != null) && (initQuery.getHighlightFields().length > 0)) {
                            header.add(
                                HighlightParams.FIELDS,
                                CmsStringUtil.arrayAsString(initQuery.getHighlightFields(), ","));
                        }
                        String formatter = initQuery.getParams(HighlightParams.FORMATTER) != null
                        ? initQuery.getParams(HighlightParams.FORMATTER)[0]
                        : null;
                        if (formatter != null) {
                            header.add(HighlightParams.FORMATTER, formatter);
                        }
                        if (initQuery.getHighlightFragsize() != 100) {
                            header.add(HighlightParams.FRAGSIZE, new Integer(initQuery.getHighlightFragsize()));
                        }
                        if (initQuery.getHighlightRequireFieldMatch()) {
                            header.add(
                                HighlightParams.FIELD_MATCH,
                                new Boolean(initQuery.getHighlightRequireFieldMatch()));
                        }
                        if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(initQuery.getHighlightSimplePost())) {
                            header.add(HighlightParams.SIMPLE_POST, initQuery.getHighlightSimplePost());
                        }
                        if (CmsStringUtil.isNotEmptyOrWhitespaceOnly(initQuery.getHighlightSimplePre())) {
                            header.add(HighlightParams.SIMPLE_PRE, initQuery.getHighlightSimplePre());
                        }
                        if (initQuery.getHighlightSnippets() != 1) {
                            header.add(HighlightParams.SNIPPETS, new Integer(initQuery.getHighlightSnippets()));
                        }
                        ResponseBuilder rb = new ResponseBuilder(
                            solrQueryRequest,
                            solrQueryResponse,
                            Collections.singletonList(highlightComponenet));
                        try {
                            rb.doHighlights = true;
                            DocListAndSet res = new DocListAndSet();
                            SchemaField idField = OpenCms.getSearchManager().getSolrServerConfiguration().getSolrSchema().getUniqueKeyField();

                            int[] luceneIds = new int[rows];
                            int docs = 0;
                            for (SolrDocument doc : solrDocumentList) {
                                String idString = (String)doc.getFirstValue(CmsSearchField.FIELD_ID);
                                int id = solrQueryRequest.getSearcher().getFirstMatch(
                                    new Term(idField.getName(), idField.getType().toInternal(idString)));
                                luceneIds[docs++] = id;
                            }
                            res.docList = new DocSlice(0, docs, luceneIds, null, docs, 0);
                            rb.setResults(res);
                            rb.setQuery(QParser.getParser(initQuery.getQuery(), null, solrQueryRequest).getQuery());
                            rb.setQueryString(initQuery.getQuery());
                            highlightComponenet.prepare(rb);
                            highlightComponenet.process(rb);
                            highlightComponenet.finishStage(rb);
                        } catch (Exception e) {
                            LOG.error(e.getMessage() + " in query: " + initQuery, new Exception(e));
                        }

                        // Make highlighting also available via the CmsSolrResultList
                        queryResponse.setResponse(solrQueryResponse.getValues());

                        highlightEndTime = System.currentTimeMillis();
                    }
                }

                result = new CmsSolrResultList(
                    initQuery,
                    queryResponse,
                    solrDocumentList,
                    resourceDocumentList,
                    start,
                    new Integer(rows),
                    end,
                    page,
                    visibleHitCount,
                    new Float(maxScore),
                    startTime,
                    highlightEndTime);
                if (LOG.isDebugEnabled()) {
                    Object[] logParams = new Object[] {
                        new Long(System.currentTimeMillis() - startTime),
                        new Long(result.getNumFound()),
                        new Long(solrTime),
                        new Long(processTime),
                        new Long(result.getHighlightEndTime() != 0 ? result.getHighlightEndTime() - startTime : 0)};
                    LOG.debug(
                        query.toString()
                            + "\n"
                            + Messages.get().getBundle().key(Messages.LOG_SOLR_SEARCH_EXECUTED_5, logParams));
                }
                if (response != null) {
                    writeResp(response, solrQueryRequest, solrQueryResponse);
                }
            } finally {
                if (solrQueryRequest != null) {
                    solrQueryRequest.close();
                }
                if (core != null) {
                    core.close();
                }
            }
            return result;
        } catch (Exception e) {
            throw new CmsSearchException(
                Messages.get().container(
                    Messages.LOG_SOLR_ERR_SEARCH_EXECUTION_FAILD_1,
                    CmsEncoder.decode(query.toString()),
                    e),
                e);
        } finally {
            if (solrQueryRequest != null) {
                solrQueryRequest.close();
            }
            // re-set thread to previous priority
            Thread.currentThread().setPriority(previousPriority);
        }

    }

    /**
     * Writes the Solr response.<p>
     *
     * @param response the servlet response
     * @param queryRequest the Solr request
     * @param queryResponse the Solr response to write
     *
     * @throws IOException if sth. goes wrong
     * @throws UnsupportedEncodingException if sth. goes wrong
     */
    private void writeResp(ServletResponse response, SolrQueryRequest queryRequest, SolrQueryResponse queryResponse)
    throws IOException, UnsupportedEncodingException {

        if (m_solr instanceof EmbeddedSolrServer) {
            SolrCore core = ((EmbeddedSolrServer)m_solr).getCoreContainer().getCore(getCoreName());
            Writer out = null;
            try {
                QueryResponseWriter responseWriter = core.getQueryResponseWriter(queryRequest);

                final String ct = responseWriter.getContentType(queryRequest, queryResponse);
                if (null != ct) {
                    response.setContentType(ct);
                }

                if (responseWriter instanceof BinaryQueryResponseWriter) {
                    BinaryQueryResponseWriter binWriter = (BinaryQueryResponseWriter)responseWriter;
                    binWriter.write(response.getOutputStream(), queryRequest, queryResponse);
                } else {
                    String charset = ContentStreamBase.getCharsetFromContentType(ct);
                    out = ((charset == null) || charset.equalsIgnoreCase(UTF8.toString()))
                    ? new OutputStreamWriter(response.getOutputStream(), UTF8)
                    : new OutputStreamWriter(response.getOutputStream(), charset);
                    out = new FastWriter(out);
                    responseWriter.write(out, queryRequest, queryResponse);
                    out.flush();
                }
            } finally {
                core.close();
                if (out != null) {
                    out.close();
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

}
