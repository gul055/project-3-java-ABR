package edu.ucsd.cs;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;

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

    public DashClient(File bwspec, String transcript) {
        this.startTime = Instant.now();
        this.httpclient = new SlowDownloader(bwspec, startTime);
        this.target = new VideoTarget(transcript, startTime);
    }

    private void streamVideo(String mpdurl) {
        try {

            // step 1: Download the mpd spec file
            DownloadedFile specfile = httpclient.slowGetURL(new URL(mpdurl));
            String spec = new String(specfile.contents);

            // Step 2: Parse the spec and pull out the URLs for each chunk at the 5 quality levels
            // How to do this was covered during the Feb 24th TA section
	    
	    File fXmlFile = new File(args[0]);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(fXmlFile);
            doc.getDocumentElement().normalize();

            assert(doc.getDocumentElement().getNodeName() == "MPD");

            NodeList repList = doc.getElementsByTagName("Representation");
            for (int repnum = 0; repnum < repList.getLength(); repnum++) {

                Node rNode = repList.item(repnum);
                assert(rNode.getNodeName() == "Representation");
                assert(rNode.getNodeType() == Node.ELEMENT_NODE);
                
                Element representation = (Element) rNode;
                System.out.println("Representation " + repnum + " Bandwidth: " + representation.getAttribute("bandwidth"));

                NodeList segmentlists = rNode.getChildNodes();
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
                                    System.out.println("    init: " + segment.getAttribute("sourceURL"));
                                } else {
                                    System.out.println("    m4s: " + segment.getAttribute("media"));
                                }
                            }
                        }
                    }
                }
            }	
	

            // Step 3: For a movie with C chunks, download chunks 1, 2, ... up to C at a given quality level
			int C = 79; // get the actual number from the mpd file
            for (int i = 1; i <= C; i++) {
                // Step 3a: Choose a quality level for chunk i
                int q = 1;   // q can be {1, 2, 3, 4, 5} based on your ABR algorithm

                // Step 3b: Download chunk i at quality level q
                URL chunkurl = new URL("Quality_q's_URL_from_the_mpd_file_goes_here");
                DownloadedFile chunk = httpclient.slowGetURL(chunkurl);

                // Step 3b: Deliver the chunk to the logger module
                // Note you might want to buffer the first few chunks to prevent
                // buffering events
                target.deliver(i, q, chunk.contents);
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
