package com.example.blendy.appnfc;

import android.app.PendingIntent;

import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.nfc.NfcAdapter;
import android.nfc.*;
import android.content.Intent;
import android.nfc.tech.NfcF;
import android.widget.Toast;
import android.util.Log;
import android.nfc.Tag;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    private NfcAdapter nfcAdapter;
    private static final String TAG = "NFCSample";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PendingIntent pi = createPendingIntent();
        nfcAdapter.enableForegroundDispatch(this, pi, null, null);
    }
    @Override
    protected void onPause() {
        super.onPause();
        nfcAdapter.disableForegroundDispatch(this); // 【4】
    }

    private PendingIntent createPendingIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK); // 【5】
        return PendingIntent.getActivity(this, 0, i, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Tag tag = (Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            // NFCタグがかざされたのとは異なる理由でインテントが飛んできた
            Log.d(TAG, "想定外のIntent受信です: action = " + intent.getAction());
            return;
        }

        NfcF nfc = NfcF.get(tag); // 【2】
        if (nfc== null) {
            // NFCFフォーマットされていないタグがかざされた
            Log.d(TAG, "NFCF形式ではないタグがかざされました。");
            return;
        }

        // カードID取得。Activityはカード認識時起動に設定しているのでここで取れる。
        byte[] felicaIDm = new byte[]{0};

        //Intent intent = getIntent();
        //Tag tag = (Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        //if (tag != null) {
        //    felicaIDm = tag.getId();
        //}
        felicaIDm = tag.getId();
        TextView textView1 = (TextView) this.findViewById(R.id.textView1);
        try {
            nfc.connect();
            byte[] req = readWithoutEncryption(felicaIDm, 10);
            Log.d(TAG, "req:"+toHex(req));
            // カードにリクエスト送信
            byte[] res = nfc.transceive(req);
            Log.d(TAG, "res:"+toHex(res));
            nfc.close();
            // 結果を文字列に変換して表示
            textView1.setText(parse(res));
        } catch (Exception e) {
            Log.e(TAG, e.getMessage() , e);
            textView1.setText(e.toString());
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
            * 履歴読み込みFelicaコマンドの取得。
            * - Sonyの「Felicaユーザマニュアル抜粋」の仕様から。
            * - サービスコードは http://sourceforge.jp/projects/felicalib/wiki/suica の情報から
            * - 取得できる履歴数の上限は「製品により異なります」。
            * @param idm カードのID
    * @param size 取得する履歴の数
    * @return Felicaコマンド
    * @throws IOException
    */
    private byte[] readWithoutEncryption(byte[] idm, int size)
            throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream(100);

        bout.write(0);           // データ長バイトのダミー
        bout.write(0x06);        // Felicaコマンド「Read Without Encryption」
        bout.write(idm);         // カードID 8byte
        bout.write(1);           // サービスコードリストの長さ(以下２バイトがこの数分繰り返す)
        bout.write(0x0f);        // 履歴のサービスコード下位バイト
        bout.write(0x09);        // 履歴のサービスコード上位バイト
        bout.write(size);        // ブロック数
        for (int i = 0; i < size; i++) {
            bout.write(0x80);    // ブロックエレメント上位バイト 「Felicaユーザマニュアル抜粋」の4.3項参照
            bout.write(i);       // ブロック番号
        }

        byte[] msg = bout.toByteArray();
        msg[0] = (byte) msg.length; // 先頭１バイトはデータ長
        return msg;
    }

    /**
     * 履歴Felica応答の解析。
     * @param res Felica応答
     * @return 文字列表現
     * @throws Exception
     */
    private String parse(byte[] res) throws Exception {
        // res[0] = データ長
        // res[1] = 0x07
        // res[2〜9] = カードID
        // res[10,11] = エラーコード。0=正常。
        if (res[10] != 0x00) throw new RuntimeException("Felica error.");

        // res[12] = 応答ブロック数
        // res[13+n*16] = 履歴データ。16byte/ブロックの繰り返し。
        int size = res[12];
        String str = "";
        for (int i = 0; i < size; i++) {
            // 個々の履歴の解析。
            Rireki rireki = Rireki.parse(res, 13 + i * 16);
            str += rireki.toString() +"\n";
        }
        return str;
    }

    private String toHex(byte[] id) {
        StringBuilder sbuf = new StringBuilder();
        for (int i = 0; i < id.length; i++) {
            String hex = "0" + Integer.toString((int) id[i] & 0x0ff, 16);
            if (hex.length() > 2)
                hex = hex.substring(1, 3);
            sbuf.append(" " + i + ":" + hex);
        }
        return sbuf.toString();
    }
}
