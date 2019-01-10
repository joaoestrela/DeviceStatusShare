package pt.up.fc.dcc.devicestatusshare;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class StartActivity extends AppCompatActivity {

    private Activity thisActivity = this;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);
        Button joinGroup = findViewById(R.id.join_group_btn);
        Button createGroup = findViewById(R.id.create_group_btn);
        joinGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new IntentIntegrator(thisActivity).initiateScan();
            }
        });
        createGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(thisActivity);
                builder.setTitle("Group Name:");
                final EditText input = new EditText(thisActivity);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                builder.setView(input);
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent myIntent = new Intent(StartActivity.this, MainActivity.class);
                        myIntent.putExtra("groupName", input.getText().toString());
                        StartActivity.this.startActivity(myIntent);
                    }
                });
                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });
                builder.show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        JSONObject jsonObject = null;
        if(result != null) {
            if(result.getContents() == null) {
                Toast.makeText(this, "Cancelled", Toast.LENGTH_LONG).show();
            } else {
                try {
                    jsonObject = new JSONObject(result.getContents());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                Intent myIntent = new Intent(StartActivity.this, MainActivity.class);
                try {
                    if(jsonObject != null && jsonObject.getString("groupName")!= null) myIntent.putExtra("groupName", jsonObject.getString("groupName"));
                    if(jsonObject != null && jsonObject.getString("groupKey")!= null) myIntent.putExtra("groupName", jsonObject.getString("groupName"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                StartActivity.this.startActivity(myIntent);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}
