package org.dspace.app.xmlui.objectmanager;

import java.util.*;
import org.dspace.content.*;
import org.dspace.core.*;

/**
 * Class that will enrich the metadata of an item
 */
public interface MetaDatumEnricher {

    void enrichMetadata(Context context, List<DCValue> metadataList);

}