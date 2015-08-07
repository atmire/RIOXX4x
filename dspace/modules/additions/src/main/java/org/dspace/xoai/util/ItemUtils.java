/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.util;

import com.lyncode.xoai.dataprovider.util.Base64Utils;
import com.lyncode.xoai.dataprovider.xml.xoai.Element;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import com.lyncode.xoai.dataprovider.xml.xoai.ObjectFactory;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.dspace.authority.AuthorityValue;
import org.dspace.authority.AuthorityValueFinder;
import org.dspace.authority.FunderAuthorityValue;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DCValue;
import org.dspace.content.Item;
import org.dspace.content.authority.Choices;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.Utils;
import org.dspace.eperson.Group;
import org.dspace.xoai.data.DSpaceDatabaseItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 *
 * @author Lyncode Development Team <dspace@lyncode.com>
 */
@SuppressWarnings("deprecation")
public class ItemUtils
{
    private static Logger log = LogManager
            .getLogger(ItemUtils.class);

    private static Element getElement(List<Element> list, String name)
    {
        for (Element e : list)
            if (name.equals(e.getName()))
                return e;

        return null;
    }
    private static Element create(ObjectFactory factory, String name)
    {
        Element e = factory.createElement();
        e.setName(name);
        return e;
    }

    private static Element.Field createValue(ObjectFactory factory,
                                             String name, String value)
    {
        Element.Field e = factory.createElementField();
        e.setValue(value);
        e.setName(name);
        return e;
    }
    public static Metadata retrieveMetadata (Item item) {
        Metadata metadata;

        DSpaceDatabaseItem dspaceItem = new DSpaceDatabaseItem(item);

        // read all metadata into Metadata Object
        ObjectFactory factory = new ObjectFactory();
        metadata = factory.createMetadata();
        DCValue[] vals = item.getMetadata(Item.ANY, Item.ANY, Item.ANY, Item.ANY);
        for (DCValue val : vals)
        {
            Element valueElem = null;
            Element schema = getElement(metadata.getElement(), val.schema);
            if (schema == null)
            {
                schema = create(factory, val.schema);
                metadata.getElement().add(schema);
            }
            valueElem = schema;

            // Has element.. with XOAI one could have only schema and value
            if (val.element != null && !val.element.equals(""))
            {
                Element element = getElement(schema.getElement(),
                        val.element);
                if (element == null)
                {
                    element = create(factory, val.element);
                    schema.getElement().add(element);
                }
                valueElem = element;

                // Qualified element?
                if (val.qualifier != null && !val.qualifier.equals(""))
                {
                    Element qualifier = getElement(element.getElement(),
                            val.qualifier);
                    if (qualifier == null)
                    {
                        qualifier = create(factory, val.qualifier);
                        element.getElement().add(qualifier);
                    }
                    valueElem = qualifier;
                }
                if(val.schema.equals("rioxxterms")&&val.element.equals("funder")){
                    try {
                        AuthorityValueFinder authorityValueFinder = new AuthorityValueFinder();
                        AuthorityValue authorityValue =authorityValueFinder.findByUID(new Context(), val.authority);
                        if(authorityValue instanceof FunderAuthorityValue){
                            String id= ((FunderAuthorityValue)authorityValue).getFunderID();
                            valueElem.getField().add(createValue(factory, "authorityID", "http://dx.doi.org/"+id));
                        }
                    } catch (SQLException e) {
                        log.error(e);
                    }


                }
            }

            // Language?
            if (val.language != null && !val.language.equals(""))
            {
                Element language = getElement(valueElem.getElement(),
                        val.language);
                if (language == null)
                {
                    language = create(factory, val.language);
                    valueElem.getElement().add(language);
                }
                valueElem = language;
            }
            else
            {
                Element language = getElement(valueElem.getElement(),
                        "none");
                if (language == null)
                {
                    language = create(factory, "none");
                    valueElem.getElement().add(language);
                }
                valueElem = language;
            }

            valueElem.getField().add(createValue(factory, "value", val.value));
            if (val.authority != null) {
                valueElem.getField().add(createValue(factory, "authority", val.authority));
                if (val.confidence != Choices.CF_NOVALUE)
                    valueElem.getField().add(createValue(factory, "confidence", val.confidence + ""));
            }
        }
        // Done! Metadata has been read!
        // Now adding bitstream info
        Element bundles = create(factory, "bundles");
        metadata.getElement().add(bundles);

        Bundle[] bs;
        try
        {
            bs = item.getBundles();
            for (Bundle b : bs)
            {
                Element bundle = create(factory, "bundle");
                bundles.getElement().add(bundle);
                bundle.getField()
                        .add(createValue(factory, "name", b.getName()));

                Element bitstreams = create(factory, "bitstreams");
                bundle.getElement().add(bitstreams);
                Bitstream[] bits = b.getBitstreams();

                Bitstream bit = null;

                for (Bitstream bitstream : bits) {

                    if (b.getPrimaryBitstreamID() != -1) {
                        if (bit.getID() == b.getPrimaryBitstreamID()) {
                            bit = bitstream;
                        }
                    } else if (b.getName().equals("ORIGINAL")) {
                        bit = bitstream;
                    }

                }

                if (bit != null) {

                    Element bitstream = create(factory, "bitstream");
                    bitstreams.getElement().add(bitstream);
                    String url = "";
                    String bsName = bit.getName();
                    String sid = String.valueOf(bit.getSequenceID());
                    String baseUrl = ConfigurationManager.getProperty("oai",
                            "bitstream.baseUrl");
                    String handle = null;
                    // get handle of parent Item of this bitstream, if there
                    // is one:
                    Bundle[] bn = bit.getBundles();
                    if (bn.length > 0)
                    {
                        Item bi[] = bn[0].getItems();
                        if (bi.length > 0)
                        {
                            handle = bi[0].getHandle();
                        }
                    }
                    if (bsName == null)
                    {
                        String ext[] = bit.getFormat().getExtensions();
                        bsName = "bitstream_" + sid
                                + (ext.length > 0 ? ext[0] : "");
                    }
                    if (handle != null && baseUrl != null)
                    {
                        url = baseUrl + "/bitstream/"
                                + handle + "/"
                                + sid + "/"
                                + URLUtils.encode(bsName);
                    }
                    else
                    {
                        url = URLUtils.encode(bsName);
                    }

                    String cks = bit.getChecksum();
                    String cka = bit.getChecksumAlgorithm();
                    String oname = bit.getSource();
                    String name = bit.getName();
                    String description = bit.getDescription();

                            addEmbargoField(bit,bitstream);

                    if (name != null)
                        bitstream.getField().add(
                                createValue(factory, "name", name));
                    if (oname != null)
                        bitstream.getField().add(
                                createValue(factory, "originalName", name));
                    bitstream.getField().add(
                            createValue(factory, "format", bit.getFormat()
                                    .getMIMEType()));
                    bitstream.getField().add(
                            createValue(factory, "size", "" + bit.getSize()));
                    bitstream.getField().add(createValue(factory, "url", url));
                    bitstream.getField().add(
                            createValue(factory, "checksum", cks));
                    bitstream.getField().add(
                            createValue(factory, "checksumAlgorithm", cka));
                    bitstream.getField().add(
                            createValue(factory, "sid", bit.getSequenceID()
                                    + ""));
                }
            }
        }
        catch (SQLException e1)
        {
            e1.printStackTrace();
        }


        // Other info
        Element other = create(factory, "others");

        other.getField().add(
                createValue(factory, "handle", item.getHandle()));
        other.getField().add(
                createValue(factory, "identifier", dspaceItem.getIdentifier()));
        other.getField().add(
                createValue(factory, "lastModifyDate", item
                        .getLastModified().toString()));
        metadata.getElement().add(other);

        // Repository Info
        Element repository = create(factory, "repository");
        repository.getField().add(
                createValue(factory, "name",
                        ConfigurationManager.getProperty("dspace.name")));
        repository.getField().add(
                createValue(factory, "mail",
                        ConfigurationManager.getProperty("mail.admin")));
        metadata.getElement().add(repository);

        // Licensing info
        Element license = create(factory, "license");
        Bundle[] licBundles;
        try
        {
            licBundles = item.getBundles(Constants.LICENSE_BUNDLE_NAME);
            if (licBundles.length > 0)
            {
                Bundle licBundle = licBundles[0];
                Bitstream[] licBits = licBundle.getBitstreams();
                if (licBits.length > 0)
                {
                    Bitstream licBit = licBits[0];
                    InputStream in;
                    try
                    {
                        in = licBit.retrieve();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        Utils.bufferedCopy(in, out);
                        license.getField().add(
                                createValue(factory, "bin",
                                        Base64Utils.encode(out.toString())));
                        metadata.getElement().add(license);
                    }
                    catch (AuthorizeException e)
                    {
                        log.warn(e.getMessage(), e);
                    }
                    catch (IOException e)
                    {
                        log.warn(e.getMessage(), e);
                    }
                    catch (SQLException e)
                    {
                        log.warn(e.getMessage(), e);
                    }

                }
            }
        }
        catch (SQLException e1)
        {
            log.warn(e1.getMessage(), e1);
        }

        return metadata;
    }
    private static Element.Field createValue(
            String name, String value)
    {
        Element.Field e = new Element.Field();
        e.setValue(value);
        e.setName(name);
        return e;
    }

    private static void addEmbargoField(Bitstream bit, Element bitstream) throws SQLException {
        Context context = new Context();

        List<ResourcePolicy> policies = AuthorizeManager.getPoliciesActionFilter(context, bit, Constants.READ);
        Group group = Group.find(context, 0);

        for (ResourcePolicy policy : policies) {
            if (group.equals(policy.getGroup())) {
                Date startDate = policies.get(0).getStartDate();

                if (startDate!=null && startDate.after(new Date())) {
                    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
                    bitstream.getField().add(
                            createValue("embargo", formatter.format(startDate)));
                }
            }
        }
        context.abort();
    }
}
