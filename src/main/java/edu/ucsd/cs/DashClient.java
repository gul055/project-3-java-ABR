package edu.ucsd.cs;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    public long videoLength = 0;
    public double initialBufferTime = 0.0;
    public double sumBandWidth = 0.0;

    private URL chunkurl;
    private long sTime;
    private long eTime;
    private long totalBufferTime;
    private DownloadedFile chunk;
    private double bufferTimeLen;

    private long durationInMs;
    private long chunkSize;
    private long currBandWidth;
    
    
    private Hashtable<Integer, ArrayList<String>> segTable= new Hashtable<>();
    private ArrayList<Integer> bandwidthTable = new ArrayList<>();
    private ArrayList<Integer> bufferQualitylist = new ArrayList<>();
    private ArrayList<byte[]> deliverLists = new ArrayList<>();

    public DashClient(File bwspec, String transcript) {
        this.startTime = Instant.now();
        this.httpclient = new SlowDownloader(bwspec, startTime);
        this.target = new VideoTarget(transcript, startTime);
    }

    private void streamVideo(String mpdurl) {
        try {

            // step 1: Download the mpd spec file
            URL urlLink = new URL(mpdurl);
            String urlLinkString = urlLink.toString();
            urlLinkString = urlLinkString.substring(0, urlLinkString.lastIndexOf('/')+1);
            //System.out.println("urlLinkString: " + urlLinkString);

            sTime = System.nanoTime();
            DownloadedFile specfile = httpclient.slowGetURL(urlLink);
            eTime = System.nanoTime();

            durationInMs = TimeUnit.NANOSECONDS.toMillis(eTime - sTime);


            String spec = new String(specfile.contents);

            // Step 2: Parse the spec and pull out the URLs for each chunk at the 5 quality levels
            // How to do this was covered during the Feb 24th TA section


	        InputStream stream = new ByteArrayInputStream(spec.getBytes(StandardCharsets.UTF_8));
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(stream);
            doc.getDocumentElement().normalize();

            assert(doc.getDocumentElement().getNodeName() == "MPD");

            NodeList repList = doc.getElementsByTagName("Representation");

            ArrayList<String> segList = new ArrayList<>(); //create a list storing segs

            for (int repnum = 0; repnum < repList.getLength(); repnum++) {

                Node rNode = repList.item(repnum);
                assert(rNode.getNodeName() == "Representation");
                assert(rNode.getNodeType() == Node.ELEMENT_NODE);
                
                Element representation = (Element) rNode;
                String bw = new String(representation.getAttribute("bandwidth"));
                //System.out.println("Representation " + repnum + " Bandwidth: " + bw);

                bandwidthTable.add(Integer.parseInt(bw)); //bandwidth as a key

                NodeList segmentlists = rNode.getChildNodes();
                segList = new ArrayList<>();
                for (int i = 0; i < segmentlists.getLength(); i++) {
                    Node segmentlist = segmentlists.item(i);

                    if (segmentlist.getNodeType() == Node.ELEMENT_NODE) {
                        //System.out.println("  " + segmentlist.getNodeName());

                        NodeList segments = segmentlist.getChildNodes();
                        for (int j = 0; j < segments.getLength(); j++) {
                            Node segmentNode = segments.item(j);

                            if (segmentNode.getNodeType() == Node.ELEMENT_NODE) {
                                Element segment = (Element) segmentNode;
                                if (segment.getNodeName() == "Initialization") {
                                    segList.add(urlLinkString + segment.getAttribute("sourceURL"));
                                    //System.out.println("    init: " + urlLinkString + segment.getAttribute("sourceURL"));
                                } else {
                                    segList.add(urlLinkString + segment.getAttribute("media"));
                                    //System.out.println("    m4s: " + urlLinkString + segment.getAttribute("media"));
                                }
                            }
                        }
                    }
                }
                segTable.put(Integer.parseInt(bw), segList);
            }

            //System.out.println(segTable.toString());
            Collections.sort(bandwidthTable); //sort the bandwidth from low to high in order to get the quality number
            /*for (int i = 0; i < bandwidthTable.size(); i++) {
                System.out.println("quality: " + (i+1) + " bandwidth: " + bandwidthTable.get(i));
            }*/

            // Step 3: For a movie with C chunks, download chunks 1, 2, ... up to C at a given quality level
            int q = 1; // default quality
            ArrayList<String> seglists = new ArrayList<>();

            if (segTable.containsKey(bandwidthTable.get(q-1))) {
                seglists = segTable.get(bandwidthTable.get(q-1));
                chunkNum = seglists.size(); // get the actual number from the mpd file
                videoLength = 2000 * (chunkNum-1); //in milliseconds
                initialBufferTime = videoLength * 0.18; //can be changed

                //System.out.println("chunkNum: " + chunkNum + " videoLength: " + videoLength + " initialBufferTime: " + initialBufferTime);
            } else {
                System.err.println("There is no segments in default quality!");
                System.exit(1);
            }
                
            initialBufferTime -= durationInMs; //mpd download time

            //Time to first frame
            //no sure if I need more chunk to determine bandwidth
            //no sure how many chunks need to download in rebuffering events

            //first few segs
            for (int i = 0; i < chunkNum; i++) {
                //check if its above the 20% time of initial buffer
                if (initialBufferTime < 2000) {
                    break;
                }
                chunkurl = new URL(segTable.get(bandwidthTable.get(q-1)).get(i));
                //System.out.println("ulr: " + chunkurl.toString());

                sTime = System.nanoTime();
                chunk = httpclient.slowGetURL(chunkurl);
                eTime = System.nanoTime();

                deliverLists.add(chunk.contents); //place the chunk and ready to delivery
                bufferQualitylist.add(q); //place the current quality for delivery use

                durationInMs = TimeUnit.NANOSECONDS.toMillis(eTime - sTime);
                
                /*
                chunkSize = chunk.contents.length * 8; //not gonna use, probably...just for testing
                if (durationInMs > 0) {
                currBandWidth = chunkSize/durationInMs;
                }
                sumBandWidth += currBandWidth;
                System.out.println("chunkSize: " + chunkSize + " durationInMs: " + durationInMs + " currBandWidth: " + currBandWidth 
                + " sumBandWidth " + sumBandWidth); */

                bufferTimeLen = initialBufferTime - durationInMs; //calculate the ratio changed

                // if bandwidth is stable or bandwidth is really big, we want to increase the quality of initial chunks
                if (bufferTimeLen/initialBufferTime >= 0.85) {
                    if (q < 5) {
                        q += 1;
                    }
                }
                initialBufferTime = bufferTimeLen;
                totalBufferTime += 2000; //that is the time we can have in advance
                //System.out.println("initialBufferTime: " + initialBufferTime);
            }

            //just for testing
            /*
            long estBandWidth = Math.round(sumBandWidth / deliverLists.size());
            System.out.println("estBandWidth: " + estBandWidth);
            */

            for(int i = 0; i < deliverLists.size(); i++) {
                target.deliver(i, bufferQualitylist.get(i), deliverLists.get(i));
                //System.out.println("Delivering chunk Num: " + i);
            }

            //start to download rest of chunks and deliver

            for (int i = deliverLists.size(); i < chunkNum; i++) {
                // Step 3a: Choose a quality level for chunk i
                //q = 3;   // q can be {1, 2, 3, 4, 5} based on your ABR algorithm
                //depend on bandwidth???

                // Step 3b: Download chunk i at quality level q
                int quality = bandwidthTable.get(q-1);
                //System.out.println("I am trying to download chunk: " + i + " with quality: " + q + " bandwidth: " + quality);
                //need to parse?
                chunkurl = new URL(segTable.get(quality).get(i));
                //System.out.println("ulr: " + chunkurl.toString());

                sTime = System.nanoTime();
                chunk = httpclient.slowGetURL(chunkurl);
                eTime = System.nanoTime();

                durationInMs = TimeUnit.NANOSECONDS.toMillis(eTime - sTime);

                /*
                chunkSize = chunk.contents.length;
                currBandWidth = chunkSize/durationInMs;
                System.out.println("currBandWidth: " + currBandWidth);*/


                // Step 3b: Deliver the chunk to the logger module
                // Note you might want to buffer the first few chunks to prevent
                // buffering events if happened, how many chunks need to be rebufferred?
                target.deliver(i, q, chunk.contents);

                //sent out 2sec video, but we need to minus the download time, which remain the buffertime we had.
                totalBufferTime = totalBufferTime - durationInMs + 2000;
                //System.out.println("totalBufferTime: " + totalBufferTime + " durationInMs: " + durationInMs);

                //we want to change the quality due to the download time and the buffer time we left, 6000 is a good number :>
                if (durationInMs > 2000 && totalBufferTime < 6000) {
                    if (q > 1) {
                        q -= 1;
                    } else {
                        q = 1;
                    }
                } else if (durationInMs <= 2000 && totalBufferTime >= 6000) {
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
