package edu.yale.sml.logic;

import edu.yale.sml.model.DataLists;
import edu.yale.sml.model.OrbisRecord;
import edu.yale.sml.model.SearchResult;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.beanutils.Converter;
import org.apache.commons.beanutils.converters.DateConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: odin
 * Date: 11/9/13
 * Time: 10:34 PM
 * To change this template use File | Settings | File Templates.
 */
public class CatalogInit {

    final static Logger logger = LoggerFactory.getLogger(BasicShelfScanEngine.class);

    private static final String NULL_BARCODE_STRING = "00000000";


    public static DataLists processCatalogList(List<SearchResult> list) throws InvocationTargetException,
            IllegalAccessException
    {
        List<String> barocodesAdded = new ArrayList<String>();
        List<OrbisRecord> badBarcodes = new ArrayList<OrbisRecord>();
        DataLists dataLists = new DataLists();

        for (SearchResult searchResult : list)
        {
            // e.g. for a barcode of legit length, but no result in Orbis

            if (searchResult.getResult().size() == 0)
            {
                /*
                if (searchResult.getId().contains(NULL_BARCODE_STRING))
                {
                    nullBarcodes++;
                }
                */
                OrbisRecord catalogObj = new OrbisRecord();
                catalogObj.setITEM_BARCODE(searchResult.getId());
                catalogObj.setDISPLAY_CALL_NO("Bad Barcode");
                catalogObj.setNORMALIZED_CALL_NO("Bad Barcode");
                catalogObj.setSUPPRESS_IN_OPAC("N/A");
                badBarcodes.add(catalogObj);

                continue; // skip full object populating
            }

            for (Map<String, Object> m : searchResult.getResult())
            {
                OrbisRecord catalogObj = new OrbisRecord();
                java.sql.Date date = null;
                Converter dc = new DateConverter(date);
                ConvertUtils.register(dc, java.sql.Date.class); // sql bug?
                BeanUtils.populate(catalogObj, m);

                // logger.debug("Added:" + catalogObj.getITEM_BARCODE());

                // Not sure what to do if CN Type null
                if (catalogObj.getCALL_NO_TYPE() == null)
                {
                    logger.debug("CN TYPE null for :" + catalogObj.getITEM_BARCODE());
                }

                if (catalogObj.getITEM_STATUS_DESC() == null
                        && catalogObj.getITEM_STATUS_DATE() == null
                        && (catalogObj.getNORMALIZED_CALL_NO() == null)
                        || catalogObj.getDISPLAY_CALL_NO() == null)
                {
                    logger.debug("Ignoring completely null record for Lauen"
                            + catalogObj.getITEM_BARCODE());
                    continue;
                }

                if (catalogObj.getITEM_STATUS_DESC() == null)
                {
                    logger.debug("ITEM_STATUS_DESC null for:" + catalogObj.getITEM_BARCODE());
                    continue;
                }

                // check if valid item status. This may cause duplicate entries:
                if (Rules.isValidItemStatus(catalogObj.getITEM_STATUS_DESC()))
                {

                    // not sure how useful it is because valid items seem to
                    // fetch only one row from Orbis (unlike invalid)

                    if (barocodesAdded.contains(catalogObj.getITEM_BARCODE())
                            && !catalogObj.getITEM_BARCODE().contains(NULL_BARCODE_STRING))
                    {
                        logger.debug("Already contains valid status item. Perhaps occurs twice!: "
                                + catalogObj.getITEM_BARCODE());
                        // check if repeat takes care of prior
                        catalogObj.setDISPLAY_CALL_NO(catalogObj.getDISPLAY_CALL_NO() + " REPEAT ");
                        dataLists.getCatalogAsList().add(catalogObj);

                    }
                    else
                    {
                        dataLists.getCatalogAsList().add(catalogObj);
                        barocodesAdded.add(catalogObj.getITEM_BARCODE());
                    }
                }
                // e.g. for barcode with Status 'Hold Status'
                else
                // if not valid item status
                {
                    printStatuses(catalogObj);

                    logger.debug("Discarding? :" + catalogObj.getITEM_BARCODE());

                    if (barocodesAdded.contains(catalogObj.getITEM_BARCODE()) == false)
                    {
                        logger.debug("Adding barcode anyway despite invalid item Status : "
                                + catalogObj.getITEM_BARCODE());
                        dataLists.getCatalogAsList().add(catalogObj);
                        barocodesAdded.add(catalogObj.getITEM_BARCODE());
                    }

                    else if (barocodesAdded.contains(catalogObj.getITEM_BARCODE()))
                    {
                        logger.debug("Already contains this item");
                        Date existingItemStatusDate = null;
                        OrbisRecord outdatedObject = findOlderItemStatusDateObject(
                                dataLists.getCatalogAsList(), catalogObj.getITEM_BARCODE());
                        if (outdatedObject != null)
                        {
                            existingItemStatusDate = outdatedObject.getITEM_STATUS_DATE();
                        }
                        else
                        {
                            logger.debug("Outdated object null!");
                        }

                        if (catalogObj.getITEM_STATUS_DATE() != null
                                && outdatedObject != null
                                && catalogObj.getITEM_STATUS_DATE().compareTo(
                                existingItemStatusDate) > 0)
                        {
                            logger.debug("Item has more recent date:"
                                    + catalogObj.getITEM_BARCODE()
                                    + ", so it's replacing the older enttity");
                            dataLists.getCatalogAsList().remove(outdatedObject);
                            dataLists.getCatalogAsList().add(catalogObj);
                        }

                        // e.g. Missing 5-5-55 vs 'Not Charged' with status date
                        // wont' get here if item_status_desc for existing item
                        // is not null:

                        if (catalogObj.getITEM_STATUS_DATE() != null && outdatedObject == null)
                        {
                            OrbisRecord priorWithNullDate = findOlderItemStatusDesc(
                                    dataLists.getCatalogAsList(), catalogObj.getITEM_BARCODE());

                            if (priorWithNullDate != null) // &&
                            // Rules.isValidItemStatus(priorWithNullDate.getITEM_STATUS_DESC()))
                            {
                                logger.debug("Adding more recent invalid, and discarding older valid or invalid w/ null status date!");
                                dataLists.getCatalogAsList().remove(priorWithNullDate);
                                dataLists.getCatalogAsList().add(catalogObj);
                            }
                            else
                            {
                                logger.debug("Not sure what to do with item : "
                                        + catalogObj.getITEM_BARCODE());
                            }
                        }
                    }
                }
            }
        }
        dataLists.setNullResultBarcodes(badBarcodes);
        return dataLists; //done!
    }

    private static OrbisRecord findOlderItemStatusDesc(List<OrbisRecord> catalogAsList, String item_BARCODE)
    {
        // assuming there's only one;
        for (OrbisRecord o : catalogAsList)
        {
            if (o.getITEM_BARCODE().equals(item_BARCODE))
            {
                if (o.getITEM_STATUS_DESC() != null
                        && o.getITEM_STATUS_DESC().toString().length() > 1)
                {
                    return o;
                }
            }
        }
        return null;
    }

    private static OrbisRecord findOlderItemStatusDateObject(List<OrbisRecord> catalogAsList,
                                                      String item_BARCODE)
    {
        // assuming there's only one;
        for (OrbisRecord o : catalogAsList)
        {
            if (o.getITEM_BARCODE().equals(item_BARCODE))
            {
                if (o.getITEM_STATUS_DATE() != null
                        && o.getITEM_STATUS_DATE().toString().length() > 1)
                {
                    return o;
                }
            }
        }

        return null;
    }

    /**
     * Prints status desc and date
     *
     * @param item
     */
    private static void printStatuses(final OrbisRecord item)
    {
        StringBuffer sb = new StringBuffer();
        sb.append(item.getITEM_BARCODE());

        if (item.getITEM_STATUS_DESC() == null)
        {
        }
        else
        {
            sb.append(" status_desc: " + item.getITEM_STATUS_DESC());
        }

        if (item.getITEM_STATUS_DATE() == null)
        {
            sb.append(" , Null status date");
        }
        else
        {
            sb.append(" ,status_date : " + item.getITEM_STATUS_DATE());
        }

        logger.debug(sb.toString());
    }


}