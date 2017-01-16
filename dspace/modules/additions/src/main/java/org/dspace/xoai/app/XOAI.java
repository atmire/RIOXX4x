/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.app;

import com.lyncode.xoai.dataprovider.exceptions.*;
import com.lyncode.xoai.dataprovider.util.*;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.text.ParseException;
import java.util.*;
import java.util.Date;
import org.apache.commons.cli.*;
import org.apache.log4j.LogManager;
import org.apache.log4j.*;
import org.apache.solr.client.solrj.*;
import org.apache.solr.client.solrj.SolrQuery.*;
import org.apache.solr.common.*;
import org.dspace.authorize.*;
import org.dspace.content.*;
import org.dspace.content.Collection;
import org.dspace.core.*;
import org.dspace.storage.rdbms.*;
import org.dspace.xoai.data.*;
import org.dspace.xoai.exceptions.*;
import org.dspace.xoai.solr.*;
import org.dspace.xoai.solr.exceptions.*;
import org.dspace.xoai.util.*;

/**
 * 
 * @author Lyncode Development Team <dspace@lyncode.com>
 */
@SuppressWarnings("deprecation")
public class XOAI
{
    private static Logger log = LogManager.getLogger(XOAI.class);

    private Context _context;

    private boolean _optimize;

    private boolean _verbose;
    
    private boolean _clean;


    public XOAI(Context context, boolean optimize, boolean clean, boolean verbose)
    {
        _context = context;
        _optimize = optimize;
        _clean = clean;
        _verbose = verbose;
    }

    public XOAI(Context ctx, boolean hasOption)
    {
        _context = ctx;
        _verbose = hasOption;
    }

    private void println(String line)
    {
        System.out.println(line);
    }

    public int index() throws DSpaceSolrIndexerException
    {
        int result = 0;
        try
        {

            if (_clean)  {
                clearIndex();
                System.out.println("Using full import.");
                this.indexAll();
            } else {
                SolrQuery solrParams = new SolrQuery("*:*")
                        .addField("item.lastmodified")
                        .addSortField("item.lastmodified", ORDER.desc).setRows(1);
    
                SolrDocumentList results = DSpaceSolrSearch.query(solrParams);
                if (results.getNumFound() == 0)
                {
                    System.out.println("There are no indexed documents, using full import.");
                    result = this.indexAll();
                }
                else
                    result = this.index((Date) results.get(0).getFieldValue("item.lastmodified"));
                
            }
            DSpaceSolrServer.getServer().commit();
            
            
            if (_optimize)
            {
                println("Optimizing Index");
                DSpaceSolrServer.getServer().optimize();
                println("Index optimized");
            }
            
            // Set last compilation date
            XOAICacheManager.setLastCompilationDate(new Date());
            return result;
        }
        catch (DSpaceSolrException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
        catch (SolrServerException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
        catch (IOException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
    }

    private int index(Date last) throws DSpaceSolrIndexerException
    {
        System.out
                .println("Incremental import. Searching for documents modified after: "
                        + last.toString());

        String sqlQuery = "SELECT item_id FROM item WHERE in_archive=TRUE AND discoverable=TRUE AND last_modified > ?";
        if(DatabaseManager.isOracle()){
                sqlQuery = "SELECT item_id FROM item WHERE in_archive=1 AND discoverable=1 AND last_modified > ?";
        }

        try
        {
            TableRowIterator iterator = DatabaseManager
                    .query(_context,
                            sqlQuery,
                            new java.sql.Timestamp(last.getTime()));
            return this.index(iterator);
        }
        catch (SQLException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
    }

    private int indexAll() throws DSpaceSolrIndexerException
    {
        System.out.println("Full import");
        try
        {

            String sqlQuery = "SELECT item_id FROM item WHERE in_archive=TRUE AND discoverable=TRUE";
            if(DatabaseManager.isOracle()){
                sqlQuery = "SELECT item_id FROM item WHERE in_archive=1 AND discoverable=1";
            }

            TableRowIterator iterator = DatabaseManager.query(_context,
                    sqlQuery);
            return this.index(iterator);
        }
        catch (SQLException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
    }

    private int index(TableRowIterator iterator)
            throws DSpaceSolrIndexerException
    {
        try
        {
            int i = 0;
            SolrServer server = DSpaceSolrServer.getServer();
            while (iterator.hasNext())
            {
                try
                {
                    server.add(this.index(Item.find(_context, iterator.next()
                            .getIntColumn("item_id"))));
                    
                    _context.clearCache();
                }
                catch (SQLException ex)
                {
                    log.error(ex.getMessage(), ex);
                }
                catch (MetadataBindException e)
                {
                    log.error(e.getMessage(), e);
                } catch (ParseException e) 
                {
                    log.error(e.getMessage(), e);
				}
                i++;
                if (i % 100 == 0) System.out.println(i+" items imported so far...");
            }
            System.out.println("Total: "+i+" items");
            server.commit();
            return i;
        }
        catch (SQLException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
        catch (SolrServerException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
        catch (IOException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
    }
    
    private SolrInputDocument index(Item item) throws SQLException, MetadataBindException, ParseException
    {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("item.id", item.getID());
        boolean pub = this.isPublic(item);
        doc.addField("item.public", pub);
        String handle = item.getHandle();
        doc.addField("item.handle", handle);
        doc.addField("item.lastmodified", DateUtils.toSolrDate(item.getLastModified()));
        doc.addField("item.submitter", item.getSubmitter().getEmail());
        doc.addField("item.deleted", item.isWithdrawn() ? "true" : "false");
        for (Collection col : item.getCollections())
            doc.addField("item.collections",
                    "col_" + col.getHandle().replace("/", "_"));
        for (Community com : XOAIDatabaseManager.flatParentCommunities(item))
            doc.addField("item.communities",
                    "com_" + com.getHandle().replace("/", "_"));

        DCValue[] allData = item.getMetadata(Item.ANY, Item.ANY, Item.ANY,
                Item.ANY);
        for (DCValue dc : allData)
        {
            String key = "metadata." + dc.schema + "." + dc.element;
            if (dc.qualifier != null)
            {
                key += "." + dc.qualifier;
            }
            doc.addField(key, dc.value);
            if (dc.authority != null) {
                doc.addField(key + ".authority", dc.authority);
                doc.addField(key + ".confidence", dc.confidence + "");
            }
        }


        
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MarshallingUtils.writeMetadata(out, ItemUtils.retrieveMetadata(item));
            doc.addField("item.compile", out.toString());

        if (_verbose)
        {
            println("Item with handle "+handle+" indexed");
        }
        
        boolean hasBitstream = false;

        for (Bundle b : item.getBundles("ORIGINAL")){
            if(b.getBitstreams().length>0) {
                hasBitstream = true;
            }
        }

        doc.addField("item.hasbitstream", hasBitstream);

        return doc;
    }

    private boolean isPublic(Item item) {
        boolean pub = false;
        try {
            //Check if READ access is allowed on this Item
            pub = AuthorizeManager.authorizeActionBoolean(_context, item, Constants.READ);
        } catch (SQLException ex) {
            log.error(ex.getMessage());
        }
        return pub;
    }

    private static boolean getKnownExplanation(Throwable t)
    {
        if (t instanceof ConnectException)
        {
            System.err.println("Solr server ("
                    + ConfigurationManager.getProperty("oai", "solr.url")
                    + ") is down, turn it on.");
            return true;
        }

        return false;
    }

    private static boolean searchForReason(Throwable t)
    {
        if (getKnownExplanation(t))
            return true;
        if (t.getCause() != null)
            return searchForReason(t.getCause());
        return false;
    }

    private static void clearIndex() throws DSpaceSolrIndexerException
    {
        try
        {
            System.out.println("Clearing index");
            DSpaceSolrServer.getServer().deleteByQuery("*:*");
            DSpaceSolrServer.getServer().commit();
            System.out.println("Index cleared");
        }
        catch (SolrServerException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
        catch (IOException ex)
        {
            throw new DSpaceSolrIndexerException(ex.getMessage(), ex);
        }
    }

    private static void cleanCache()
    {
        System.out.println("Purging cached OAI responses.");
        XOAICacheManager.deleteCachedResponses();
    }

    private static final String COMMAND_IMPORT = "import";
    private static final String COMMAND_CLEAN_CACHE = "clean-cache";
    private static final String COMMAND_COMPILE_ITEMS = "compile-items";
    private static final String COMMAND_ERASE_COMPILED_ITEMS = "erase-compiled-items";
    
    public static void main(String[] argv)
    {
        try
        {
            CommandLineParser parser = new PosixParser();
            Options options = new Options();
            options.addOption("c", "clear", false, "Clear index before indexing");
            options.addOption("o", "optimize", false,
                    "Optimize index at the end");
            options.addOption("v", "verbose", false, "Verbose output");
            options.addOption("h", "help", false, "Shows some help");
            options.addOption("n", "number", true, "FOR DEVELOPMENT MUST DELETE");
            CommandLine line = parser.parse(options, argv);

            String[] validSolrCommands = { COMMAND_IMPORT, COMMAND_CLEAN_CACHE };
            String[] validDatabaseCommands = { COMMAND_CLEAN_CACHE, COMMAND_COMPILE_ITEMS, COMMAND_ERASE_COMPILED_ITEMS };
            
            
            boolean solr = true; // Assuming solr by default
            solr = !("database").equals(ConfigurationManager.getProperty("oai", "storage"));
            
            
            boolean run = false;
            if (line.getArgs().length > 0) {
                if (solr) {
                    if (Arrays.asList(validSolrCommands).contains(line.getArgs()[0])) {
                        run = true;
                    }
                } else {
                    if (Arrays.asList(validDatabaseCommands).contains(line.getArgs()[0])) {
                        run = true;
                    }
                }
            }
            
            if (!line.hasOption('h') && run) {
                    System.out.println("OAI 2.0 manager action started");
                    long start = System.currentTimeMillis();
                    
                    String command = line.getArgs()[0];
                    
                    if (COMMAND_IMPORT.equals(command)) {
                        Context ctx = new Context();
                        XOAI indexer = new XOAI(ctx,
                                line.hasOption('o'), 
                                line.hasOption('c'), 
                                line.hasOption('v'));

                        int imported = indexer.index();
                        if (imported > 0) cleanCache();
                        
                        ctx.abort();
                    } else if (COMMAND_CLEAN_CACHE.equals(command)) {
                        cleanCache();
                    } else if (COMMAND_COMPILE_ITEMS.equals(command)) {

                        Context ctx = new Context();
                        XOAI indexer = new XOAI(ctx, line.hasOption('v'));

                        indexer.compile();
                        
                        cleanCache();
                        
                        ctx.abort();
                    } else if (COMMAND_ERASE_COMPILED_ITEMS.equals(command)) {
                        cleanCompiledItems();
                        cleanCache();
                    }

                    System.out.println("OAI 2.0 manager action ended. It took "
                            + ((System.currentTimeMillis() - start) / 1000)
                            + " seconds.");
            } else {
                usage();
            }
        }
        catch (Throwable ex)
        {
            if (!searchForReason(ex))
            {
                ex.printStackTrace();
            }
            log.error(ex.getMessage(), ex);
        }
    }
    
    private static void cleanCompiledItems()
    {
        System.out.println("Purging compiled items");
        XOAICacheManager.deleteCompiledItems();
    }

    private void compile() throws CompilingException
    {
        ItemIterator iterator;
        try
        {
            Date last = XOAICacheManager.getLastCompilationDate();
            
            if (last == null) {
                System.out.println("Retrieving all items to be compiled");
                iterator = Item.findAll(_context);
            } else {
                System.out.println("Retrieving items modified after "+last+" to be compiled");
                String query = "SELECT * FROM item WHERE last_modified>?";
                iterator = new ItemIterator(_context, DatabaseManager.query(_context, query, new java.sql.Date(last.getTime())));
            }
            
            while (iterator.hasNext()) {
                Item item = iterator.next();
                if (_verbose) System.out.println("Compiling item with handle: "+ item.getHandle());
                XOAICacheManager.compileItem(new DSpaceDatabaseItem(item));
                _context.clearCache();
            }
            
            XOAICacheManager.setLastCompilationDate(new Date());
        }
        catch (SQLException e)
        {
            throw new CompilingException(e);
        }
        System.out.println("Items compiled");
    }

    private static void usage()
    {
        boolean solr = true; // Assuming solr by default
        solr = !("database").equals(ConfigurationManager.getProperty("oai", "storage"));
        
        if (solr) {
            System.out.println("OAI Manager Script");
            System.out.println("Syntax: oai <action> [parameters]");
            System.out.println("> Possible actions:");
            System.out.println("     "+COMMAND_IMPORT+" - To import DSpace items into OAI index and cache system");
            System.out.println("     "+COMMAND_CLEAN_CACHE+" - Cleans the OAI cached responses");
            System.out.println("> Parameters:");
            System.out.println("     -o Optimize index after indexing ("+COMMAND_IMPORT+" only)");
            System.out.println("     -c Clear index ("+COMMAND_IMPORT+" only)");
            System.out.println("     -v Verbose output");
            System.out.println("     -h Shows this text");
        } else {
            System.out.println("OAI Manager Script");
            System.out.println("Syntax: oai <action> [parameters]");
            System.out.println("> Possible actions:");
            System.out.println("     "+COMMAND_CLEAN_CACHE+" - Cleans the OAI cached responses");
            System.out.println("     "+COMMAND_COMPILE_ITEMS+" - Compiles all DSpace items");
            System.out.println("     "+COMMAND_ERASE_COMPILED_ITEMS+" - Erase the OAI compiled items");
            System.out.println("> Parameters:");
            System.out.println("     -v Verbose output");
            System.out.println("     -h Shows this text");
        }
        
    }
}
