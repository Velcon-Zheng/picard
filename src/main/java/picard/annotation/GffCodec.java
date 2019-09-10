package picard.annotation;

import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.LocationAware;

import htsjdk.tribble.AbstractFeatureCodec;
import htsjdk.tribble.Feature;
import htsjdk.tribble.FeatureCodecHeader;
import htsjdk.tribble.SimpleFeature;
import htsjdk.tribble.annotation.Strand;
import htsjdk.tribble.readers.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picard.PicardException;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public abstract class GffCodec extends AbstractFeatureCodec<GtfFeature, LineIterator> {
    final Logger logger = LogManager.getLogger(GffCodec.class);

    public static final String FIELD_DELIMITER = "\t";

    private static final int NUM_FIELDS = 9;

    private static final int CHROMOSOME_NAME_INDEX = 0;
    private static final int ANNOTATION_SOURCE_INDEX = 1;
    private static final int FEATURE_TYPE_INDEX = 2;
    private static final int START_LOCATION_INDEX = 3;
    private static final int END_LOCATION_INDEX = 4;
    private static final int GENOMIC_STRAND_INDEX = 6;
    private static final int GENOMIC_PHASE_INDEX = 7;
    private static final int EXTRA_FIELDS_INDEX = 8;

    private final Set<String> FILE_EXTENSIONS;

    private static final String COMMENT_START = "#";

    private static final String FLUSH_DIRECTIVE = "###";

    private final LinkedList<GtfFeature> activeFeatures = new LinkedList<>();
    private final LinkedList<GtfFeature> featuresToFlush = new LinkedList<>();
    /* discontinuous features can have multiple lines representing the same feature, with the same ID in GFF.
    For this implementation the discontinous features are split into separate features, with children only associated with the portion on the discontinous feature they overlap.
     */
    private final Map<String, Set<GtfFeature>> activeFeaturesWithIDs = new HashMap<>();

    private int currentLineNum = 0;

     GffCodec(final Set<String> fileExtensions) {
        super(GtfFeature.class);
        FILE_EXTENSIONS = fileExtensions;
    }

    @Override
    public GtfFeature decode(final LineIterator lineIterator) {
        if(!lineIterator.hasNext()) {
            featuresToFlush.addAll(activeFeatures);
            for (final GtfFeature feature : activeFeatures) {
                activeFeaturesWithIDs.get(feature.getID()).remove(feature);
                if (activeFeaturesWithIDs.get(feature.getID()).isEmpty()) {
                    activeFeaturesWithIDs.remove(feature.getID());
                }
            }
            activeFeatures.clear();
            return featuresToFlush.poll();
        }

        final String line = lineIterator.next();
        currentLineNum++;

        if (line.startsWith(COMMENT_START)) {
            return null;
        }

        final String[] splitLine = line.split(FIELD_DELIMITER, -1);

        if (splitLine.length != NUM_FIELDS) {
            throw new PicardException("Found an invalid number of columns in the given GENCODE file on line "
                    + currentLineNum + " - Given: " + splitLine.length + " Expected: " + NUM_FIELDS + " : " + line);
        }

        try {
            final String contig = splitLine[CHROMOSOME_NAME_INDEX];
            final String source = splitLine[ANNOTATION_SOURCE_INDEX];
            final String type = splitLine[FEATURE_TYPE_INDEX];
            final int start = Integer.valueOf(splitLine[START_LOCATION_INDEX]);
            final int end = Integer.valueOf(splitLine[END_LOCATION_INDEX]);
            final int phase = splitLine[GENOMIC_PHASE_INDEX].equals(".")? -1 : Integer.valueOf(splitLine[GENOMIC_PHASE_INDEX]);
            final Strand strand = Strand.decode(splitLine[GENOMIC_STRAND_INDEX]);
            final Map<String, String> attributes = parseAttributes(splitLine[EXTRA_FIELDS_INDEX]);
            final GtfFeature thisFeature = new GtfFeature(contig, source, type, start, end, strand, phase, attributes);
            final List<String> parentIDs = attributes.get("Parent") != null? Arrays.asList(attributes.get("Parent").split(",")) : new ArrayList<>();
            final String id = attributes.get("ID");
            for (final String parentID : parentIDs) {
                final Set<GtfFeature> parents = activeFeaturesWithIDs.get(parentID);
                if (parents == null) {
                    throw new PicardException("Could not find feature with ID " + parentID);
                }
                final List<GtfFeature> overlappingParents = parents.stream().filter(p -> p.overlaps(thisFeature)).collect(Collectors.toList());
                if (overlappingParents.isEmpty()) {
                    throw new PicardException("Could not find feautre with ID " + parentID + " overlapping " + thisFeature.getContig() + ":" + thisFeature.getStart() + "-" + thisFeature.getEnd());
                }
                for (final GtfFeature parent : overlappingParents) {
                    parent.addChild(thisFeature);
                    thisFeature.addParent(parent);
                }
            }
            if (!thisFeature.hasParents()) {
                activeFeatures.add(thisFeature);
            }
            if (activeFeaturesWithIDs.containsKey(id)) {
                activeFeaturesWithIDs.get(id).add(thisFeature);
            } else {
                activeFeaturesWithIDs.put(id, new HashSet<>(Collections.singleton(thisFeature)));
            }

            featuresToFlush.poll();
        } catch (final NumberFormatException ex ) {
            throw new PicardException("Cannot read integer value for start/end position!");
        }
        return null;
    }

    protected abstract Map<String,String> parseAttributes(final String attributesString);

    @Override
    public Feature decodeLoc(LineIterator lineIterator) {
        final String line = lineIterator.next();

        if (line.startsWith(COMMENT_START)) {
            return null;
        }

        final String[] splitLine = line.split(FIELD_DELIMITER, -1);

        try {
            return new SimpleFeature(splitLine[CHROMOSOME_NAME_INDEX], Integer.valueOf(splitLine[START_LOCATION_INDEX]), Integer.valueOf(splitLine[END_LOCATION_INDEX]));
        } catch (final NumberFormatException ex ) {
            throw new PicardException("Cannot read integer value for start/end position!");
        }
    }

    protected abstract String getFirstLineStart();

    @Override
    public boolean canDecode(final String inputFilePath) {
        boolean canDecode;
        try {
            // Simple file and name checks to start with:
            Path p = IOUtil.getPath(inputFilePath);

            canDecode = FILE_EXTENSIONS.contains(FilenameUtils.getExtension(p.toString()));

            if (canDecode) {

                // Crack open the file and look at the top of it:
                try ( BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(p))) ) {

                    String line = br.readLine();
                    //first line must begin with ##gtf-version 2
                    if (!line.startsWith(getFirstLineStart())) {
                        return false;
                    }
                    while (line.startsWith(COMMENT_START)) {
                        line = br.readLine();
                        if ( line == null ) {
                            return false;
                        }
                    }

                    // make sure line conforms to gtf spec
                    final String[] fields = line.split(FIELD_DELIMITER);

                    canDecode &= fields.length == NUM_FIELDS;

                    if (canDecode) {
                        // check that start and end fields are integers
                        try {
                            final int start = Integer.parseInt(fields[3]);
                            final int end = Integer.parseInt(fields[4]);
                        } catch (NumberFormatException | NullPointerException nfe) {
                            canDecode = false;
                        }

                        // check for strand

                        canDecode &= fields[GENOMIC_STRAND_INDEX].equals("+") || fields[GENOMIC_STRAND_INDEX].equals("-") || fields[GENOMIC_STRAND_INDEX].equals(".");
                    }
                }

            }
        }
        catch (final FileNotFoundException ex) {
            logger.warn("File does not exist! - " + inputFilePath + " - returning can decode as failure.");
            canDecode = false;
        }
        catch (final IOException ex) {
            logger.warn("Caught IOException on file: " + inputFilePath + " - returning can decode as failure.");
            canDecode = false;
        }

        return canDecode;
    }

    @Override
    public FeatureCodecHeader readHeader(LineIterator lineIterator) throws IOException {

        List<String> header = new ArrayList<>();
        while(lineIterator.hasNext()) {
            String line = lineIterator.peek();
            if (line.startsWith(COMMENT_START)) {
                header.add(line);
                lineIterator.next();
            } else {
                break;
            }
        }

        return new FeatureCodecHeader(header, FeatureCodecHeader.NO_HEADER_END);
    }

    @Override
    public LineIterator makeSourceFromStream(final InputStream bufferedInputStream) {
        return new LineIteratorImpl(new SynchronousLineReader(bufferedInputStream));
    }

    @Override
    public LocationAware makeIndexableSourceFromStream(final InputStream bufferedInputStream) {
        return new AsciiLineReaderIterator(AsciiLineReader.from(bufferedInputStream));
    }

    @Override
    public boolean isDone(final LineIterator lineIterator) {
        return !lineIterator.hasNext() && activeFeatures.isEmpty() && featuresToFlush.isEmpty();
    }

    @Override
    public void close(final LineIterator lineIterator) {
        CloserUtil.close(lineIterator);
    }


}
