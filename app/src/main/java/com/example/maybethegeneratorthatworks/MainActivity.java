package com.example.maybethegeneratorthatworks;

import android.media.Image;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.clarifai.clarifai_android_sdk.datamodels.Input;
import com.clarifai.clarifai_android_sdk.datamodels.Model;
import com.clarifai.clarifai_android_sdk.utils.Error;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TagGenerator";

    /** Constant to perform a read file request. */
    private static final int READ_REQUEST_CODE = 42;

    /** Constant to request an image capture. */
    private static final int IMAGE_CAPTURE_REQUEST_CODE = 1;

    /** Constant to request permission to write to the external storage device. */
    private static final int REQUEST_WRITE_STORAGE = 112;

    /** Request queue for our network requests. */
    private RequestQueue requestQueue;

    /** Whether we can write to public storage. */
    private boolean canWriteToPublicStorage = false;

    /**
     * OnCreate method.
     * @param savedInstanceState state saved by the activity last time it was paused
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestQueue = Volley.newRequestQueue(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final ImageButton addPhoto = findViewById(R.id.addPhoto);
        addPhoto.setOnClickListener(v -> {
            Log.d(TAG, "Open gallery button clicked");
            startOpenFile();
        });
        final Button processImage = findViewById(R.id.processImage);
        processImage.setOnClickListener(v -> {
            Log.d(TAG, "Process Image button clicked");
            startProcessImage();
        });
        final ProgressBar progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.INVISIBLE);

        /*
         * Here we check for permission to write to external storage and request it if necessary.
         * Normally you would not want to do this on ever start, but we want to be persistent
         * since it makes development a lot easier.
         */
        canWriteToPublicStorage = (ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        Log.d(TAG, "Do we have permission to write to external storage: "
                + canWriteToPublicStorage);
        if (!canWriteToPublicStorage) {
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
        }
    }

    /**
     * Called when an intent that we requested has finished.
     *
     * In our case, we either asked the file browser to open a file, or the camera to take a
     * photo. We respond appropriately below.
     *
     * @param requestCode the code that we used to make the request
     * @param resultCode a code indicating what happened: success or failure
     * @param resultData any data returned by the activity
     */
    @Override
    public void onActivityResult(final int requestCode, final int resultCode,
                                 final Intent resultData) {

        // If something went wrong we simply log a warning and return
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "onActivityResult with code " + requestCode + " failed");
            if (requestCode == IMAGE_CAPTURE_REQUEST_CODE) {
                photoRequestActive = false;
            }
            return;
        }

        // Otherwise we get a link to the photo either from the file browser or the camera,
        Uri currentPhotoURI;
        if (requestCode == READ_REQUEST_CODE) {
            currentPhotoURI = resultData.getData();
        } else if (requestCode == IMAGE_CAPTURE_REQUEST_CODE) {
            currentPhotoURI = Uri.fromFile(currentPhotoFile);
            photoRequestActive = false;
            if (canWriteToPublicStorage) {
                addPhotoToGallery(currentPhotoURI);
            }
        } else {
            Log.w(TAG, "Unhandled activityResult with code " + requestCode);
            return;
        }

        // Now load the photo into the view
        Log.d(TAG, "Photo selection produced URI " + currentPhotoURI);
        loadPhoto(currentPhotoURI);
    }

    /**
     * Start an open file dialog to look for image files.
     */
    private void startOpenFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, READ_REQUEST_CODE);
    }
    /** Current file that we are using for our image request. */
    private boolean photoRequestActive = false;

    /** Whether a current photo request is being processed. */
    private File currentPhotoFile = null;

    /** Initiate the image recognition process. */
    private void startProcessImage() {
        if (currentBitmap == null) {
            Toast.makeText(getApplicationContext(), "No image selected",
                    Toast.LENGTH_LONG).show();
            Log.w(TAG, "No image selected");
            return;
        }

        /*
         * Launch our background task which actually makes the request. It will call
         * finishProcessImage below with the JSON string when it finishes.
         */
        new Tasks.ProcessImageTask(MainActivity.this, requestQueue)
                .execute(currentBitmap);

    }
    /**
     * Add a photo to the gallery so that we can use it later.
     *
     * @param toAdd URI of the file to add
     */
    void addPhotoToGallery(final Uri toAdd) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(toAdd);
        this.sendBroadcast(mediaScanIntent);
        Log.d(TAG, "Added photo to gallery: " + toAdd);
    }


    /**
     * Process the result from making the API call.
     *
     * @param jsonResult the result of the API call as a string
     * */
    protected void finishProcessImage(final String jsonResult) {
        /*
         * Pretty-print the JSON into the bottom text-view to help with debugging.
         */
        TextView textView = findViewById(R.id.tagView);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonParser jsonParser = new JsonParser();
        JsonElement jsonElement = jsonParser.parse(jsonResult);
        String prettyJsonString = gson.toJson(jsonElement);
        textView.setText(prettyJsonString);
        textView.setVisibility(View.VISIBLE);
    }

    /** Current bitmap we are working with. */
    private Bitmap currentBitmap;


    /**
     * Process a photo.
     *
     * Resizes an image and loads it into the UI.
     *
     * @param currentPhotoURI URI of the image to process
     */
    private void loadPhoto(final Uri currentPhotoURI) {
        if (currentPhotoURI == null) {
            Toast.makeText(getApplicationContext(), "No image selected",
                    Toast.LENGTH_LONG).show();
            Log.w(TAG, "No image selected");
            return;
        }
        String uriScheme = currentPhotoURI.getScheme();

        byte[] imageData;
        try {
            assert uriScheme != null;
            switch (uriScheme) {
                case "file":
                    imageData = FileUtils.readFileToByteArray(new File(currentPhotoURI.getPath()));
                    break;
                case "content":
                    InputStream inputStream = getContentResolver().openInputStream(currentPhotoURI);
                    assert inputStream != null;
                    imageData = IOUtils.toByteArray(inputStream);
                    inputStream.close();
                    break;
                default:
                    Toast.makeText(getApplicationContext(), "Unknown scheme " + uriScheme,
                            Toast.LENGTH_LONG).show();
                    return;
            }
        } catch (IOException e) {
            Toast.makeText(getApplicationContext(), "Error processing file",
                    Toast.LENGTH_LONG).show();
            Log.w(TAG, "Error processing file: " + e);
            return;
        }

        /*
         * Resize the image appropriately for the display.
         */
        final ImageView photoView = findViewById(R.id.addPhoto);
        int targetWidth = photoView.getWidth();
        int targetHeight = photoView.getHeight();

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageData, 0, imageData.length, decodeOptions);

        int actualWidth = decodeOptions.outWidth;
        int actualHeight = decodeOptions.outHeight;
        int scaleFactor = Math.min(actualWidth / targetWidth, actualHeight / targetHeight);

        BitmapFactory.Options modifyOptions = new BitmapFactory.Options();
        modifyOptions.inJustDecodeBounds = false;
        modifyOptions.inSampleSize = scaleFactor;

        // Actually draw the image
        updateCurrentBitmap(BitmapFactory.decodeByteArray(imageData,
                0, imageData.length, modifyOptions), true);
    }

    /**
     * Update the currently displayed image, resetting the image information (caption, metadata,
     * cat/dog indicators) if requested.
     *
     * @param setCurrentBitmap the new bitmap to display
     * @param resetInfo whether to reset the image information
     */
    void updateCurrentBitmap(final Bitmap setCurrentBitmap, final boolean resetInfo) {
        currentBitmap = setCurrentBitmap;
        ImageView photoView = findViewById(R.id.addPhoto);
        photoView.setImageBitmap(currentBitmap);
    }

    /**
     * Get a new file location for saving.
     *
     * @return the path to the new file or null of the create failed
     */
    File getSaveFilename() {
        String imageFileName = "MP3_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File storageDir;
        if (canWriteToPublicStorage) {
            storageDir = Environment
                    .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        } else {
            storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        }
        try {
            return File.createTempFile(imageFileName, ".jpg", storageDir);
        } catch (IOException e) {
            Log.w(TAG, "Problem saving file: " + e);
            return null;
        }
    }

    /**
     * Gets the Volley request queue for this activity. For testing purposes only.
     * @return the internal web request queue
     */
    RequestQueue getRequestQueue() {
        return requestQueue;
    }

    /**
     * Sets the Volley request queue used by this activity. For testing purposes only.
     * @param newQueue the request queue to install
     */
    void setRequestQueue(final RequestQueue newQueue) {
        requestQueue = newQueue;
    }
}
