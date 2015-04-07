package com.conaltuohy.xprocz;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;


import org.apache.commons.codec.binary.Base64;


import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult; 
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import net.sf.saxon.s9api.SaxonApiException;

import com.xmlcalabash.core.XProcRuntime;
import com.xmlcalabash.core.XProcConfiguration;
import com.xmlcalabash.util.Input;
import com.xmlcalabash.runtime.XPipeline;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XdmNode;
import com.xmlcalabash.io.ReadablePipe;
import java.io.OutputStreamWriter;
import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmSequenceIterator;
import java.io.UnsupportedEncodingException;
import java.io.Reader;
import java.io.InputStreamReader;
import javax.servlet.http.Part;
import javax.servlet.annotation.MultipartConfig;
import javax.xml.parsers.ParserConfigurationException;
/**
 * Servlet implementation class XProcZServlet
 * The RetailerServlet is a host for HTTP server applications written in XProc.
 */
@MultipartConfig
public class XProcZServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final String XPROC_STEP_NS = "http://www.w3.org/ns/xproc-step";
	
	private final static SAXTransformerFactory transformerFactory = (SAXTransformerFactory) TransformerFactory.newInstance();
	private final static DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
	private DocumentBuilder builder;
	
    /**
     * @see HttpServlet#HttpServlet()
     */
     public XProcZServlet() {
        super();
    }
    
    public void init() throws ServletException {
    	 try {
    	 	 factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    	 	 builder = factory.newDocumentBuilder();
    	 } catch (ParserConfigurationException pce) {
    	 	 // should not happen as support for FEATURE_SECURE_PROCESSING is mandatory
    	 	 throw new ServletException(pce);
    	 }
    }
    
    private Document parseXML(InputStream inputStream) throws SAXException, IOException {
    	 // TODO should a parse failure trigger re-processing as plain text?
    	 Document document = builder.parse(inputStream);
    	 inputStream.close();
    	 return document;
    }

    /** Respond to an HTTP request using an XProc pipeline.
	* • Create an XML document representing the HTTP request
	* • Transform the document using the XProc pipeline, returning the 
	* result to the HTTP client.
	 * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	@Override
	public void service(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		
		// Create a stream to send response XML to the HTTP client
		OutputStream os = resp.getOutputStream();

		// Create a document describing the HTTP request,
		// from request parameters, headers, etc.
		// to be the input document for the XProc pipeline.
		Document requestXML = null;
		try {
			requestXML = factory.newDocumentBuilder().newDocument();
		} catch (ParserConfigurationException documentCreationFailed) {
			fail(documentCreationFailed, "Error creating DOM Document");
		}
		
		// Populate the XML document from the HTTP request data
		/*
		<request xmlns="http://www.w3.org/ns/xproc-step"
		  method = NCName
		  href? = anyURI
		  detailed? = boolean
		  status-only? = boolean
		  username? = string
		  password? = string
		  auth-method? = string
		  send-authorization? = boolean
		  override-content-type? = string>
			 (c:header*,
			  (c:multipart |
				c:body)?)
		</request>
		*/
		try {
			Element request = requestXML.createElementNS(XPROC_STEP_NS, "request");
			requestXML.appendChild(request);
			String queryString = req.getQueryString();
			String requestURI = req.getRequestURL().toString();
			if (queryString != null) {
				requestURI += "?" + queryString;
			};
			request.setAttribute("method", req.getMethod());
			request.setAttribute("href", requestURI);
			request.setAttribute("detailed", "true");
			request.setAttribute("status-only", "false");
			if (req.getRemoteUser() != null) {
				request.setAttribute("username", req.getRemoteUser()); 
				// NB password not available; pipeline would need to process the Authorization header
			};
			if (req.getAuthType() != null) {
				request.setAttribute("auth-method", req.getAuthType()); 
			};
			
			// the HTTP request headers
			for (String name : Collections.list(req.getHeaderNames())) {	
				Element header = requestXML.createElementNS(XPROC_STEP_NS, "header");
				request.appendChild(header);
				header.setAttribute("name", name);
				header.setAttribute("value", req.getHeader(name));
			}
			
			// the request body or parts
			if (req.getContentType() == null || req.getContentLength() == 0) {
				// no HTTP message body ⇒ no c:body or c:multipart elements
			} else if (req.getContentType().startsWith("multipart/form-data;")) {
				// content is multipart
				// create c:multipart
				String boundary = req.getContentType().substring("multipart/form-data; boundary=".length());
				Element multipart = requestXML.createElementNS(XPROC_STEP_NS, "multipart");
				multipart.setAttribute("content-type", req.getContentType());
				request.appendChild(multipart);
				multipart.setAttribute("boundary", boundary); 
				// for each part, create a c:body
				for (Part part: req.getParts()) {
					Element body = requestXML.createElementNS(XPROC_STEP_NS, "body");
					multipart.appendChild(body);			
					String partContentType = part.getContentType();
					if (partContentType == null) {
						partContentType = "text/plain";
					}
					String disposition = part.getHeader("Content-Disposition");
					if (disposition != null) {
						body.setAttribute("disposition", disposition);
					}
					body.setAttribute("content-type", partContentType);
					String contentId = part.getHeader("Content-ID");
					if (contentId != null) {
						body.setAttribute("id", contentId);
					}
					String contentDescription = part.getHeader("Content-Description");
					if (contentDescription != null) {
						body.setAttribute("description", contentDescription);
					}
					// TODO allow badly formed XML content to fall back to being processed as text
					// insert the actual content of the part
					if (isXMLMediaType(partContentType)) {
						// parse XML
						Document uploadedDocument = parseXML(part.getInputStream());
						// TODO also import top-level comments, processing instructions, etc?
						body.appendChild(
							body.getOwnerDocument().adoptNode(
								uploadedDocument.getDocumentElement()
							)
						);
					} else if (isTextMediaType(partContentType)) {
						// otherwise if text then copy it unparsed
						// <c:body content-type="text/plain">This &amp; that</c:body>
						InputStream inputStream = part.getInputStream();
						body.appendChild(
							requestXML.createTextNode(
								readText(inputStream, getCharacterEncoding(req))
							)
						);
						inputStream.close();
					} else {
						// Base64 encode binary data
						body.setAttribute("encoding", "base64");
						InputStream inputStream = part.getInputStream();
						body.appendChild(
							requestXML.createTextNode(
								readBinary(inputStream)
							)
						);
						inputStream.close();
					}
				}
			} else {
				// content is simple
				// create c:body element
				Element body = requestXML.createElementNS(XPROC_STEP_NS, "body");
				request.appendChild(body);
				body.setAttribute("content-type", req.getContentType());
				String contentType = req.getContentType();
				if (isXMLMediaType(contentType)) {
					// TODO if it's XML then parse it and place root element inside
					// <c:body content-type="application/rdf+xml"><rdf:RDF etc.../></c:body>
					Document uploadedDocument = parseXML(req.getInputStream());
					// TODO also import top-level comments, processing instructions, etc?
					body.appendChild(
						body.getOwnerDocument().adoptNode(
							uploadedDocument.getDocumentElement()
						)
					);
				} else if (isTextMediaType(contentType)) {
					// otherwise if text then copy it unparsed
					// <c:body content-type="text/plain">This &amp; that</c:body>
					InputStream inputStream = req.getInputStream();
					body.appendChild(
						requestXML.createTextNode(
							readText(inputStream, getCharacterEncoding(req))
						)
					);
					inputStream.close();
				} else {
				// ... or if binary then base64 encode it 
				// <c:body content-type="application/pdf" encoding = "base64">...</c:body>
					body.setAttribute("encoding", "base64");
					InputStream inputStream = req.getInputStream();
					body.appendChild(
						requestXML.createTextNode(
							readBinary(inputStream)
						)
					);
					inputStream.close();
				}
			}
			
			// Process the XML document which describes the HTTP request, 
			// sending the result to the HTTP client
			XProcConfiguration config = new XProcConfiguration();
			XProcRuntime runtime = new XProcRuntime(config);
			Input input = new Input(getServletContext().getRealPath("/xproc/xproc-z.xpl"));
			XPipeline pipeline = runtime.load(input);
			// wrap request DOM in Saxon XdmNode
			XdmNode inputDocument = runtime.getProcessor().newDocumentBuilder().wrap(requestXML);
			pipeline.writeTo("source", inputDocument);
			pipeline.run();
			ReadablePipe result = pipeline.readFrom("result");
			XdmNode outputDocument = result.read();
			
			// generate HTTP Response from pipeline output
			QName responseName = new QName(XPROC_STEP_NS, "response");
			XdmNode rootElement = (XdmNode) outputDocument.axisIterator(Axis.CHILD, responseName).next();
			String statusAttribute = rootElement.getAttributeValue( new QName("status"));
			resp.setStatus(Integer.valueOf(statusAttribute));
			QName bodyName = new QName(XPROC_STEP_NS, "body");
			XdmNode bodyElement = (XdmNode) rootElement.axisIterator(Axis.CHILD, bodyName).next();
			if (bodyElement != null) {
				// there is an entity body to return
				String encoding = bodyElement.getAttributeValue( new QName("encoding") );
				String contentType = bodyElement.getAttributeValue( new QName ("content-type") );
				String contentDisposition = bodyElement.getAttributeValue( new QName ("disposition") );
				if (contentDisposition != null) {
					resp.addHeader("Content-Disposition", contentDisposition);
				}
				XdmSequenceIterator content = bodyElement.axisIterator(Axis.CHILD);
				resp.setContentType(contentType);
				QName headerName = new QName(XPROC_STEP_NS, "header");
				XdmSequenceIterator headers = rootElement.axisIterator(Axis.CHILD, headerName);
				QName nameName = new QName("name");
				QName valueName = new QName("value");
				while (headers.hasNext()) {
					XdmNode headerNode = (XdmNode) headers.next();
					resp.addHeader(headerNode.getAttributeValue(nameName), headerNode.getAttributeValue(valueName));
				}
				if ("base64".equals(encoding)) {
					// decode base64 encoded binary data and stream to http client
					os.write(
						Base64.decodeBase64(
							content.next().toString()
						)
					);
				} else if (isXMLMediaType(contentType)) {
					// output the sequence of XML nodes within the c:body element using toString to
					// produce an XML serialization of each one
					OutputStreamWriter writer = new OutputStreamWriter(os);
					while (content.hasNext()) {
						XdmItem contentItem = content.next();
						writer.write(contentItem.toString());
					}
					writer.flush();
				} else {
					// output plain text content within the c:body element by writing its string value
					OutputStreamWriter writer = new OutputStreamWriter(os);
					while (content.hasNext()) {
						XdmItem contentItem = content.next();
						writer.write(contentItem.getStringValue());
					}
					writer.flush();
				}
			}
		} catch (Exception pipelineFailed) {
			getServletContext().log("Pipeline failed", pipelineFailed);
			resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			resp.setContentType("text/plain");
			PrintWriter writer = new PrintWriter(os);
			if (pipelineFailed instanceof SaxonApiException) {
				SaxonApiException e = (SaxonApiException) pipelineFailed;
				writer.print("Error ");
				writer.print(e.getErrorCode());
				/*
				writer.print(" in module ");
				writer.print(e.getSystemId());
				writer.print(" on line ");
				writer.print(e.getLineNumber());
				*/
			}
			//writer.println(pipelineFailed.getMessage());
			pipelineFailed.printStackTrace(writer);
			writer.flush();
		}
		os.close();		
	}
	
	// logs an exception and re-throws it as a servlet exception
	private void fail(Exception e, String message) throws ServletException {
			getServletContext().log(message, e);
			throw new ServletException(message, e);
	}
	
	private String getCharacterEncoding(HttpServletRequest req) {
		String encoding = req.getCharacterEncoding();
		if (encoding == null) {
			return "ISO-8859-1";
		} else {
			return encoding;
		}
	}
	
	/**
	* Determine whether content is, or can be treated as, plain text
	*/
	private boolean isTextMediaType(String mediaType) {
		return (
			mediaType.startsWith("text/") ||
			mediaType.equals("application/x-www-form-urlencoded") ||
			(
				mediaType.startsWith("application/") && 
				mediaType.endsWith("+json")
			)
		);
	}
	
	
	/**
	* Determine whether content is already XML, or alternatively, will need to be encoded as XML
	* See <a href="https://tools.ietf.org/html/rfc7303">RFC7303</a>
	*/
	private boolean isXMLMediaType(String mediaType) {
		return (
			mediaType.equals("application/xml") ||
			mediaType.equals("application/xml-external-parsed-entity") ||
			mediaType.equals("text/xml") ||
			mediaType.equals("text/xml-external-parsed-entity") ||
			(
				mediaType.startsWith("application/") && 
				mediaType.endsWith("+xml")
			)
		);
	}
	
	// Read text from the input stream
	private String readText(InputStream inputStream, String characterEncoding) 
		throws IOException, UnsupportedEncodingException {
		char[] buffer = new char[1024];
		StringBuilder builder = new StringBuilder();
		Reader reader = new InputStreamReader(inputStream, characterEncoding);
		int charactersRead = reader.read(buffer, 0, buffer.length);
		while (charactersRead > -1) {
			builder.append(buffer, 0, charactersRead);
			charactersRead = reader.read(buffer, 0, buffer.length);
		}
		return builder.toString();
	}
	
	// Read binary data from the input stream and return Base64 encoded text
	private String readBinary(InputStream inputStream) 
		throws IOException {
		byte[] buffer = new byte[1024];
		StringBuilder builder = new StringBuilder();
		int bytesRead = inputStream.read(buffer, 0, buffer.length);
		while (bytesRead > -1) {
			byte[] readBuffer;
			if (bytesRead == 1024) {
				readBuffer = buffer;
			} else {
				readBuffer = Arrays.copyOfRange(buffer, 0, bytesRead);
			}
			builder.append(Base64.encodeBase64String(readBuffer));
			bytesRead = inputStream.read(buffer, 0, buffer.length);
		}
		return builder.toString();
	}

}
