// Copyright 2019 The MediaPipe Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.mediapipe.apps.facemeshgpu;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.framework.AndroidPacketCreator;
import com.google.mediapipe.framework.AndroidPacketGetter;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketGetter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Main activity of MediaPipe face mesh app. */
public class MainActivity extends com.google.mediapipe.apps.basic.MainActivity {
  private static final String TAG = "MainActivity";

  private static final String INPUT_NUM_FACES_SIDE_PACKET_NAME = "num_faces";
  private static final String OUTPUT_LANDMARKS_STREAM_NAME = "multi_face_landmarks";
  // Max number of faces to detect/process.
  private static final int NUM_FACES = 1;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    AndroidPacketCreator packetCreator = processor.getPacketCreator();
    Map<String, Packet> inputSidePackets = new HashMap<>();
    inputSidePackets.put(INPUT_NUM_FACES_SIDE_PACKET_NAME, packetCreator.createInt32(NUM_FACES));
    processor.setInputSidePackets(inputSidePackets);

    TextView tv = findViewById(R.id.tv);
    ImageView resultImgVw = findViewById(R.id.resultImgVw);
    FrameLayout frameLayout = findViewById(R.id.preview_display_layout);

    resultImgVw.setVisibility(View.GONE);

    // To show verbose logging, run:
    // adb shell setprop log.tag.MainActivity VERBOSE
    if (Log.isLoggable(TAG, Log.INFO)) {
      processor.addPacketCallback(
        OUTPUT_LANDMARKS_STREAM_NAME,
        (packet) -> {
          Log.i(TAG, packet.toString());
          List<NormalizedLandmarkList> faces = PacketGetter.getProtoVector(packet, NormalizedLandmarkList.parser());
          if (faces.size() > 0) {
            List<NormalizedLandmark> landmarks = faces.get(0).getLandmarkList();

            int topIndex = 10, bottomIndex = 152, leftChinIndex = 425, rightChinIndex = 205; 
            
            NormalizedLandmark top = landmarks.get(topIndex), 
              bottom = landmarks.get(bottomIndex), 
              leftChin = landmarks.get(leftChinIndex), 
              rightChin = landmarks.get(rightChinIndex);

            double[] topPoints = new double[]{top.getX(), top.getY(), top.getZ()}, 
              bottomPoints = new double[]{bottom.getX(), bottom.getY(), bottom.getZ()}, 
              leftChinPoints = new double[]{leftChin.getX(), leftChin.getY(), leftChin.getZ()}, 
              rightChinPoints = new double[]{rightChin.getX(), rightChin.getY(), rightChin.getZ()};
              
            double[] vLR = new double[]{leftChinPoints[0] - rightChinPoints[0], leftChinPoints[1] - rightChinPoints[1], leftChinPoints[2] - rightChinPoints[2]},
              vTB = new double[]{topPoints[0] - bottomPoints[0], topPoints[1] - bottomPoints[1], topPoints[2] - bottomPoints[2]};
              
            // vBF = vLR cross vTB (x = y * z)
            double[] vBF = new double[]{vLR[1]*vTB[2] - vLR[2]*vTB[1], vLR[2]*vTB[0] - vLR[0]*vTB[2], vLR[0]*vTB[1] - vLR[1]*vTB[0]};

            double[] normX = normalize(vBF), normY = normalize(vLR), normZ = normalize(vTB), angleZ = angle(normZ);

            tv.setText(String.format("x = (%.0f,\t%.0f,\t%.0f)%ny = (%.0f,\t%.0f,\t%.0f)%nz = (%.0f,\t%.0f,\t%.0f)%nangleZ = (%.0f,\t%.0f,\t%.0f)",
              normX[0], normX[1], normX[2], 
              normY[0], normY[1], normY[2], 
              normZ[0], normZ[1], normZ[2], 
              angleZ[0], angleZ[1], angleZ[2]));
            if (angleIsForward(angleZ)) {
              tv.setText(tv.getText() + "\n\n FORWARD!!");
              
              // Bitmap bm = AndroidPacketGetter.getBitmapFromRgba(packet);
              // ByteArrayOutputStream baos = new ByteArrayOutputStream();
              // bm.compress(Bitmap.CompressFormat.JPEG, 100, baos);
              // tv.setText(tv.getText() + "\n 3");
              // String base64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
              // tv.setText(tv.getText() + "\n 4");

              resultImgVw.setImageBitmap(bm);
              tv.setVisibility(View.GONE);
              frameLayout.setVisibility(View.GONE);
              resultImgVw.setVisibility(View.VISIBLE);
            }
          } else {
            tv.setText("No Face visible");
          }
        });
    }
  }

  public double[] normalize(double[] vect) {
    double size = Math.sqrt(Math.pow(vect[0], 2) + Math.pow(vect[1], 2) + Math.pow(vect[2], 2));
    return new double[]{vect[0] * 100 / size, vect[1] * 100 / size, vect[2] * 100 / size};
  }

  public double[] angle(double[] vect) {
    double size = Math.sqrt(Math.pow(vect[0], 2) + Math.pow(vect[1], 2) + Math.pow(vect[2], 2));
    return new double[]{
      Math.acos(vect[0] / size) / Math.PI * 180,
      Math.acos(vect[1] / size) / Math.PI * 180,
      Math.acos(vect[2] / size) / Math.PI * 180,
    };
  }

  public boolean angleIsForward(double[] vect) {
    int error = 5;
    return isAboutEqual(vect[0], 90, error) && 
      isAboutEqual(vect[1], 175, error) && 
      isAboutEqual(vect[2], 90, error);
  }

  public boolean isAboutEqual(double number, double approx, double error) {
    return approx - error <= number && number <= approx + error;
  }

// axis:
// x: back to front (vBF)
// y: left to right (vLR)
// z: top to bottom (vTB)

// angleZ's:
// forward: 90, 180, 90
// left:    90, 160, 75
// right:   75, 160, 90
// top:     90, 140, 45
// bottom:  90, 140, 135
}
