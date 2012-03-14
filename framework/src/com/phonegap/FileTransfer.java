/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package com.phonegap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Iterator;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;
import android.webkit.CookieManager;

import com.phonegap.api.Plugin;
import com.phonegap.api.PluginResult;

public class FileTransfer extends Plugin {

    private static final String LOG_TAG = "FileTransfer";
    public static int FILE_NOT_FOUND_ERR = 1;
    public static int INVALID_URL_ERR = 2;
    public static int CONNECTION_ERR = 3;

    /* (non-Javadoc)
    * @see com.phonegap.api.Plugin#execute(java.lang.String, org.json.JSONArray, java.lang.String)
    */
    @Override
    public PluginResult execute(String action, JSONArray args, String callbackId) {
        String source;
        String target;
        try {
            source = args.getString(0);
            target = args.getString(1);
        }
        catch (JSONException e) {
            Log.d(LOG_TAG, "Missing source or target");
            return new PluginResult(PluginResult.Status.JSON_EXCEPTION, "Missing source or target");
        }

        try {
            if (action.equals("upload")) {
                // Setup the options
                String fileKey;
                String fileName;
                String mimeType;

                fileKey = getArgument(args, 2, "file");
                fileName = getArgument(args, 3, "image.jpg");
                mimeType = getArgument(args, 4, "image/jpeg");
                JSONObject params = args.optJSONObject(5);
                boolean trustEveryone = args.optBoolean(6);
                boolean chunkedMode = args.optBoolean(7);

                FileUploadResult r = upload(source, target, fileKey, fileName, mimeType, params, trustEveryone,
		                chunkedMode);
                Log.d(LOG_TAG, "****** About to return a result from upload");
                return new PluginResult(PluginResult.Status.OK, r.toJSONObject());
            } else if (action.equals("download")) {
                JSONObject r = download(source, target);
                Log.d(LOG_TAG, "****** About to return a result from download");
                return new PluginResult(PluginResult.Status.OK, r, "window.localFileSystem._castEntry");
            } else {
                return new PluginResult(PluginResult.Status.INVALID_ACTION);
            }
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            JSONObject error = createFileTransferError(FILE_NOT_FOUND_ERR, source, target);
            return new PluginResult(PluginResult.Status.IO_EXCEPTION, error);
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            JSONObject error = createFileTransferError(INVALID_URL_ERR, source, target);
            return new PluginResult(PluginResult.Status.IO_EXCEPTION, error);
        } catch (SSLException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            Log.d(LOG_TAG, "Got my ssl exception!!!");
            JSONObject error = createFileTransferError(CONNECTION_ERR, source, target);
            return new PluginResult(PluginResult.Status.IO_EXCEPTION, error);
        } catch (IOException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            JSONObject error = createFileTransferError(CONNECTION_ERR, source, target);
            return new PluginResult(PluginResult.Status.IO_EXCEPTION, error);
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            return new PluginResult(PluginResult.Status.JSON_EXCEPTION);
        }
    }

    // always verify the host - don't check for certificate
    final static HostnameVerifier DO_NOT_VERIFY = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    /**
     * Create an error object based on the passed in errorCode
     * @param errorCode 	the error
     * @return JSONObject containing the error
     */
    private JSONObject createFileTransferError(int errorCode, String source, String target) {
        JSONObject error = null;
        try {
            error = new JSONObject();
            error.put("code", errorCode);
            error.put("source", source);
            error.put("target", target);
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
        }
        return error;
    }

    /**
     * Convenience method to read a parameter from the list of JSON args.
     * @param args			the args passed to the Plugin
     * @param position		the position to retrieve the arg from
     * @param defaultString the default to be used if the arg does not exist
     * @return String with the retrieved value
     */
    private String getArgument(JSONArray args, int position, String defaultString) {
        String arg = defaultString;
        if(args.length() >= position) {
            arg = args.optString(position);
            if (arg == null || "null".equals(arg)) {
                arg = defaultString;
            }
        }
        return arg;
    }

    /**
     * Uploads the specified file to the server URL provided using an HTTP
     * multipart request.
     * This implementation does not support debug mode (trustEveryone doesn't matter) and chunked mode.
     *
     * @param file      Full path of the file on the file system
     * @param server        URL of the server to receive the file
     * @param fileKey       Name of file request parameter
     * @param fileName      File name to be used on server
     * @param mimeType      Describes file content type
     * @param params        key:value pairs of user-defined parameters
     * @return FileUploadResult containing result of upload request
     */
    public FileUploadResult upload(String file, String server, final String fileKey, final String fileName,
            final String mimeType, JSONObject params, boolean trustEveryone, boolean chunkedMode)
		    throws IOException, SSLException {

	    if(Log.isLoggable(LOG_TAG, Log.DEBUG)) {
		    Log.d(LOG_TAG, String.format("Uploading %s to $s. FileKey is %s, fileName is %s, mimeType is %s",
				    file, server, fileKey, fileName, mimeType));
	    }

        // Create return object
        FileUploadResult result = new FileUploadResult();

	    HttpClient httpclient = new DefaultHttpClient();

	    try {
	        HttpPost httpPost = new HttpPost(server);

		     // setting cookies
			String cookie = CookieManager.getInstance().getCookie(server);
			if (cookie != null) {
				httpPost.setHeader("Cookie", cookie);
			}

		    MultipartEntity multipartEntity = new MultipartEntity();

		    // adding file
		    FileBody fileBody = new FileBody(getFile(file), fileName, mimeType, null);
			multipartEntity.addPart(fileKey, fileBody);

		    // adding parameters
		    for(Iterator it = params.keys(); it.hasNext();) {
			    String key = it.next().toString();

			    multipartEntity.addPart(key, new StringBody(params.getString(key)));
		    }

		    httpPost.setEntity(multipartEntity);

		    // executing
		    HttpResponse response = httpclient.execute(httpPost);

		    // reading response
		    String responseString = "";
		    HttpEntity responseEntity = response.getEntity();
		    if(responseEntity != null) {
			    InputStream is = responseEntity.getContent();

			    try {
					// converting the stream to a string
					// from http://weblogs.java.net/blog/pat/archive/2004/10/stupid_scanner_1.html
					responseString = new java.util.Scanner(is).useDelimiter("\\A").next();
				} catch (java.util.NoSuchElementException ignore) {
			    } finally {
				    is.close();
			    }
		    }

		    if(Log.isLoggable(LOG_TAG, Log.DEBUG)) {
				Log.d(LOG_TAG, String.format("Got response from server: %s", responseString));
		    }

            result.setResponseCode(response.getStatusLine().getStatusCode());
		    result.setResponse(responseString);

	    } catch (JSONException e) {
		    Log.e(LOG_TAG, "JSON error", e);
		    throw new IOException(e);
	    } finally {
		    try {
				httpclient.getConnectionManager().shutdown();
		    } catch (Exception ignore) {}
	    }

	    return result;
    }

	/**
	 * Get a file object from a given path
	 * @param path Full path of the file on the file system
	 * @return the file object
	 */
	private File getFile(String path) {
		if (path.startsWith("content:")) {
            throw new UnsupportedOperationException("content: protocol is not supported");
        }

		if (path.startsWith("file://")) {
            int question = path.indexOf("?");
            if (question == -1) {
                return new File(path.substring(7));
            } else {
                return new File(path.substring(7, question));
            }
        } else {
            return new File(path);
        }
	}

    /**
     * Downloads a file form a given URL and saves it to the specified directory.
     *
     * @param source        URL of the server to receive the file
     * @param target      	Full path of the file on the file system
     * @return JSONObject 	the downloaded file
     */
    public JSONObject download(String source, String target) throws IOException {
        try {
            File file = new File(target);

            // create needed directories
            file.getParentFile().mkdirs();

            // connect to server
            URL url = new URL(source);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            Log.d(LOG_TAG, "Download file:" + url);

            InputStream inputStream = connection.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead = 0;

            FileOutputStream outputStream = new FileOutputStream(file);

            // write bytes to file
            while ( (bytesRead = inputStream.read(buffer)) > 0 ) {
                outputStream.write(buffer,0, bytesRead);
            }

            outputStream.close();

            Log.d(LOG_TAG, "Saved file: " + target);

            // create FileEntry object
            FileUtils fileUtil = new FileUtils();

            return fileUtil.getEntry(file);
        } catch (Exception e) {
            Log.d(LOG_TAG, e.getMessage(), e);
            throw new IOException("Error while downloading");
        }
    }
}
