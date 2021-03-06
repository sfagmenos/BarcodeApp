/*
 * Copyright 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package karthik.BarcodeLocalizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.gdata.client.photos.PicasawebService;
import com.google.gdata.data.Link;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.media.MediaByteArraySource;
import com.google.gdata.data.media.MediaFileSource;
import com.google.gdata.data.photos.AlbumEntry;
import com.google.gdata.data.photos.GphotoEntry;
import com.google.gdata.data.photos.GphotoFeed;
import com.google.gdata.data.photos.PhotoEntry;
import com.google.gdata.data.photos.UserFeed;
import com.google.gdata.util.ServiceException;
import com.google.gdata.util.ServiceForbiddenException;

/**
 * Display personalized greeting.
 */
public class ImageUploader extends AsyncTask<Void, Void, Boolean> {
	private static final String TAG = "PicasaImageUploaderTask";
	protected Context con;
	private static final String PICASA_PREFIX = "https://picasaweb.google.com/data/feed/api/user/";
	private static final String SMARTHOME_ALBUM_NAME = "smarter";
	PicasawebService picasaService;

	// used to access UI thread for toasts
	private static Handler handler = new Handler(Looper.getMainLooper());
		
	protected String mEmail;
	protected Bitmap img;
	protected String token;
	private Location pic_location;
	private boolean WifiUploadOnly;
	private File video;
	private boolean isVideo = false;
	
	ImageUploader(Context con, String email, Bitmap bmp, String tok, Location loc, boolean WifiUploadOnly) {
		this.con = con;		
		this.mEmail = email;
		this.img = bmp;
		this.token = tok;
		this.pic_location = loc;
		this.WifiUploadOnly = WifiUploadOnly;
		isVideo = false;
	}

	ImageUploader(Context con, String email, File videoFile, String tok, Location loc, 
			boolean WifiUploadOnly) {
		this.con = con;
		this.mEmail = email;
		this.video = videoFile;
		this.token = tok;
		this.WifiUploadOnly = WifiUploadOnly;
		this.pic_location = loc;
		
		isVideo = true;
	}

	@Override
	protected Boolean doInBackground(Void... params) {
		// no upload if user wants to upload on wifi only and we are not on Wifi
		if (WifiUploadOnly && !isWifiConnected()){
			Log.i(TAG, "Upload unsuccessful - not on Wifi");
			displayToast("Upload unsuccessful - not on Wifi", Toast.LENGTH_LONG);
			// TODO: change this so it retries later instead of dropping the video
			removeTempVideoFile();
			return false;
		}
		// we are either on Wifi connection or user is fine with using mobile data
		// so go ahead with the upload
		try {
			picasaService = new PicasawebService("ImageUploader");
			picasaService.setAuthSubToken(token);

			List<AlbumEntry> albums = null;
			AlbumEntry HeliosAlbum = null;
			albums = getAlbums(mEmail);
			Log.d(TAG, "Got " + albums.size() + " albums ");
			for (AlbumEntry myAlbum : albums) {
				String albumName = myAlbum.getTitle().getPlainText();
				Log.d(TAG, "Album " + albumName + " " + myAlbum.getId());
				if (albumName.equals(SMARTHOME_ALBUM_NAME))
					HeliosAlbum = myAlbum;
			}
			if (HeliosAlbum == null)
				HeliosAlbum = createAlbum(SMARTHOME_ALBUM_NAME, "Helios SmartHome Project Pics");

			Link albumFeedLink = HeliosAlbum.getLink(
					com.google.gdata.data.Link.Rel.FEED, null);
			URL albumFeedURL = new URL(albumFeedLink.getHref());
			if(isVideo)
				uploadVideo(albumFeedURL);
			else
				uploadImage(img, albumFeedURL);

			Log.i(TAG, "Upload successful");

			return true;
		}

		catch (IOException ex) {
			onError("Following Error occured, please try again. "
					+ ex.getMessage(), ex);
		} catch (ServiceForbiddenException e) {
			onError("ServiceForbiddenException", e);
		} catch (ServiceException e) {
			onError("ServiceException", e);
		}
		removeTempVideoFile();
		return false;
	}

	protected void onError(String msg, Exception e) {
		if (e != null) {
			Log.e(TAG, "Exception: ", e);
		}
		displayToast(msg, Toast.LENGTH_SHORT);; // will be run in UI thread

	}

	public <T extends GphotoFeed> T getFeed(String feedHref, Class<T> feedClass)
			throws IOException, ServiceException {
		Log.v(TAG, "Get Feed URL: " + feedHref);
		return picasaService.getFeed(new URL(feedHref), feedClass);
	}

	public List<AlbumEntry> getAlbums(String userId) throws IOException,
			ServiceException {

		String albumUrl = PICASA_PREFIX + userId;
		UserFeed userFeed = getFeed(albumUrl, UserFeed.class);
		List<GphotoEntry> entries = userFeed.getEntries();
		List<AlbumEntry> albums = new ArrayList<AlbumEntry>();
		for (GphotoEntry entry : entries) {
			AlbumEntry ae = new AlbumEntry(entry);
			albums.add(ae);
		}

		return albums;
	}

	protected void uploadImage(Bitmap bmp, URL albumURL)
			throws IOException, ServiceException {

		Log.d(TAG, "Trying to upload image...");
		PhotoEntry myPhoto = new PhotoEntry();
		
		SimpleDateFormat s = new SimpleDateFormat("dd_MM_yyyy_hh_mm_ss");
		String timeStamp = s.format(new Date());
		String title = "Barcode_" + timeStamp;
		myPhoto.setTitle(new PlainTextConstruct(title));

		// convert bitmap to jpeg and make it a byte array
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		bmp.compress(CompressFormat.JPEG, 75, bos);

		MediaByteArraySource myMedia = new MediaByteArraySource(bos.toByteArray(), "image/jpeg");
		myPhoto.setMediaSource(myMedia);
		
		if(pic_location != null) // only set location if it is a valid Location object
			myPhoto.setGeoLocation(pic_location.getLatitude(), pic_location.getLongitude());
		
		try {
			picasaService.insert(albumURL, myPhoto);
		} catch (Exception e) {
			Log.i(TAG, "Insertion error: " + e.getMessage());
			displayToast("Insertion error: Could not upload photo ", Toast.LENGTH_SHORT);
		}
		Log.d(TAG, "Photo uploaded");
	}

	protected void uploadVideo(URL albumURL)
			throws IOException, ServiceException {

		Log.d(TAG, "Trying to upload video " + video.getName() + "...");
		PhotoEntry myVideo = new PhotoEntry();
		
		String title = video.getName();
		myVideo.setTitle(new PlainTextConstruct(title));

		// read MPEG-4 video file and make it a byte array

		MediaFileSource myMedia = new MediaFileSource(video, "video/mpeg4");
		myVideo.setMediaSource(myMedia);
		
		if(pic_location != null){ // only set location if it is a valid Location object
			myVideo.setGeoLocation(pic_location.getLatitude(), pic_location.getLongitude());
			Log.v(TAG, "Uploading video with location Lat:" + pic_location.getLatitude() + " Long:" + pic_location.getLongitude());
		}
		
		try {
			picasaService.insert(albumURL, myVideo);
			Log.d(TAG, "Uploaded video "+ video.getName());
		} catch (Exception e) {
			Log.w(TAG, "Insertion error: " + e.getMessage());
			displayToast("Video upload error: " + e.getMessage(), Toast.LENGTH_LONG);
		}
		removeTempVideoFile(); // delete the video file from storage after it has been uploaded
	}

	private AlbumEntry createAlbum(final String albumName, final String albumDescription) 
			throws IOException, ServiceException{
		
		AlbumEntry myAlbum = new AlbumEntry();
		myAlbum.setTitle(new PlainTextConstruct(albumName));
		myAlbum.setDescription(new PlainTextConstruct(albumDescription));
		URL postUrl = new URL(PICASA_PREFIX + mEmail + "/");

		AlbumEntry insertedEntry = picasaService.insert(postUrl, myAlbum);
		if (insertedEntry != null)
			Log.i(TAG, "Album: " + insertedEntry.getName()+ " created sucessfully");
		
		return insertedEntry;
	}
	
	private boolean isWifiConnected(){
		ConnectivityManager connectivity = (ConnectivityManager) con.getSystemService(Context.CONNECTIVITY_SERVICE);
        //If connectivity object is not null
        if (connectivity != null) {
            //Get network info - WIFI internet access
            NetworkInfo info = connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
 
            if (info != null) {
                //Look for whether device is currently connected to WIFI network
                if (info.isConnected()) {
                    return true;
                }
            }
        }
        return false;
	}
	
	private void displayToast(final String text, final int toast_length){
		// helper method uses the main thread to display a toast
		// we use this because if this class is used by a Service
		// as opposed to an Activity, we can't access the UI thread 
		// in the normal way using RunOnUIThread
		handler.post(new Runnable(){
			public void run(){
				Toast.makeText(con, text, toast_length).show();
			}
		});
	}
	
	private void removeTempVideoFile(){
		// remove temp video file from phone storage
		if(isVideo)
			video.delete();
	}
}
