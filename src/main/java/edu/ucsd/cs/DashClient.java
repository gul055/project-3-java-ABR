package edu.ucsd.cs;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.concurrent.TimeUnit;
import java.io.*;
import java.nio.charset.StandardCharsets;

import edu.ucsd.cs.SlowDownloader.DownloadedFile;

// xmlparsering import
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
// xmlparsering import


public final class DashClient {

    private Instant startTime;
    private SlowDownloader httpclient;
    private VideoTarget target;

    public int chunkNum = 0;
    public int videoLength = 0;
    public double initialBufferTime = 0.0;
    public double avgBandWidth = 0.0;

    private URL chunkurl;
    private long sTime;
    private long eTime;
    private DownloadedFile chunk;

    private long durationInSec;
    private long chunkSize;
    private long currBandWidth;
    
    
    private Hashtable<String, ArrayList<String>> segTable= new Hashtable<>();
    private Hashtable<Integer, String> bandwidthTable = new Hashtable<>();
    private ArrayList<byte[]> deliverLists = new ArrayList<>();

    public DashClient(File bwspec, String transcript) {
        this.startTime = Instant.now();
        this.httpclient = new SlowDownloader(bwspec, startTime);
        this.target = new VideoTarget(transcript, startTime);
    }

    private void streamVideo(String mpdurl) {
        try {

            // step 1: Download the mpd spec file
            System.out.println("Hello!");
            DownloadedFile specfile = httpclient.slowGetURL(new URL(mpdurl));
            System.out.println("Downloads complete!");
            String spec = new String(specfile.contents);

            // Step 2: Parse the spec and pull out the URLs for each chunk at the 5 quality levels
            // How to do this was covered during the Feb 24th TA section
	    try {
	    //File fXmlFile = new File(spec);
	    InputStream stream = new ByteArrayInputStream(spec.getBytes(StandardCharsets.UTF_8));
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(stream);
            doc.getDocumentElement().normalize();

            assert(doc.getDocumentElement().getNodeName() == "MPD");

            NodeList repList = doc.getElementsByTagName("Representation");
            ArrayList<String> segList = new ArrayList<>();
            for (int repnum = 0; repnum < repList.getLength(); repnum++) {

                Node rNode = repList.item(repnum);
                assert(rNode.getNodeName() == "Representation");
                assert(rNode.getNodeType() == Node.ELEMENT_NODE);
                
                Element representation = (Element) rNode;
                String bw = new String(representation.getAttribute("bandwidth"));
                System.out.println("Representation " + repnum + " Bandwidth: " + bw);
                bandwidthTable.put(repnum+1, bw);

                NodeList segmentlists = rNode.getChildNodes();
                segList.clear();
                for (int i = 0; i < segmentlists.getLength(); i++) {
                    Node segmentlist = segmentlists.item(i);

                    if (segmentlist.getNodeType() == Node.ELEMENT_NODE) {
                        System.out.println("  " + segmentlist.getNodeName());

                        NodeList segments = segmentlist.getChildNodes();
                        for (int j = 0; j < segments.getLength(); j++) {
                            Node segmentNode = segments.item(j);

                            if (segmentNode.getNodeType() == Node.ELEMENT_NODE) {
                                Element segment = (Element) segmentNode;
                                if (segment.getNodeName() == "Initialization") {
                                    segList.add(segment.getAttribute("sourceURL"));
                                    System.out.println("    init: " + segment.getAttribute("sourceURL"));
                                } else {
                                    segList.add(segment.getAttribute("media"));
                                    System.out.println("    m4s: " + segment.getAttribute("media"));
                                }
                            }
                        }
                    }
                }
                segTable.put(bw, segList);
            }	
	    }	catch (Exception e) {
		    System.out.println(e);
	    }	
            
            // Step 3: For a movie with C chunks, download chunks 1, 2, ... up to C at a given quality level
            int q = 1;
            String quality = "";
            ArrayList<String> seglists = new ArrayList<>();

            if (bandwidthTable.containsKey(q)) {
                quality = bandwidthTable.get(q);

                if (segTable.containsKey(quality)) {
                    seglists = segTable.get(quality);
                    chunkNum = seglists.size(); // get the actual number from the mpd file
                    videoLength = 2 * chunkNum; //in seconds
                    initialBufferTime = videoLength * 0.15; //can be changed
                }

            } else {
                System.err.println("There is no segments in quality 1!");
                System.exit(1);
            }
                
            //Time to first frame
            //no sure if I need more chunk to determine bandwidth
            //no sure how many chunks need to download in rebuffering events

            //first few segs
            for (int i = 0; i <= chunkNum; i++) {
                if (initialBufferTime <= 0) {
                    break;
                }
                chunkurl = new URL("http://ec2-54-184-118-202.us-west-2.compute.amazonaws.com/testHtml/" +  seglists.get(i));
                sTime = System.nanoTime();
                chunk = httpclient.slowGetURL(chunkurl);
                eTime = System.nanoTime();

                durationInSec = TimeUnit.NANOSECONDS.toSeconds(eTime - sTime);
                initialBufferTime -= durationInSec;

                deliverLists.add(chunk.contents);

                chunkSize = chunk.contents.length;
               // currBandWidth = chunkSize/durationInSec;
               // avgBandWidth += currBandWidth;
            }

            //double estBandWidth = avgBandWidth / deliverLists.size();

            for(int i = 0; i < deliverLists.size(); i++) {
                target.deliver(i, q, deliverLists.get(i));
            }

            //start to download rest of chunks and deliver
            for (int i = deliverLists.size(); i < chunkNum; i++) {
                // Step 3a: Choose a quality level for chunk i
                q = 3;   // q can be {1, 2, 3, 4, 5} based on your ABR algorithm
                //depend on bandwidth???

                // Step 3b: Download chunk i at quality level q
                quality = bandwidthTable.get(q);
                System.out.println("I am trying to download chunk");
                //need to parse?
                chunkurl = new URL("http://ec2-54-184-118-202.us-west-2.compute.amazonaws.com/testHtml/" + segTable.get(quality).get(i));
                sTime = System.nanoTime();
                chunk = httpclient.slowGetURL(chunkurl);
                eTime = System.nanoTime();

                durationInSec = TimeUnit.NANOSECONDS.toSeconds(eTime - sTime);
                chunkSize = chunk.contents.length;
               // currBandWidth = chunkSize/durationInSec;

                // Step 3b: Deliver the chunk to the logger module
                // Note you might want to buffer the first few chunks to prevent
                // buffering events if happened, how many chunks need to be rebufferred?
                target.deliver(i, q, chunk.contents);

                if (durationInSec > 2) {
                    if (q > 1) {
                        q -= 1;
                    } else {
                        q = 1;
                    }
                } else if (durationInSec < 2) {
                    if (q < 5) {
                        q += 1;
                    }
                }
            }
        } catch (MalformedURLException e) {
            System.err.println("Error with the URL");
            System.err.println(e.toString());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error downloading file");
            System.err.println(e.toString());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.out.println("MPEG-DASH Client starting");

        if (args.length != 3) {
            System.err.println("Usage: DashClient bwspec.txt transcript.txt mpd_url");
            System.exit(1);
        }

        DashClient client = new DashClient(new File(args[0]), args[1]);
        client.streamVideo(args[2]);
    }
}
