package com.winlator.d2;

import com.winlator.R;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;





public class D2NewsActivity extends D2Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(getString(R.string.news_title));
        title.setTextSize(24);
        D2Style.heading(title);
        content.addView(title);
        TextView note = new TextView(this);
        // Thor D2: own changelog in the app's language, and thanks to D2Android.
        note.setText(getString(R.string.thord2_news_thanks));
        note.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        note.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        note.setPadding(0, dp(4), 0, dp(8));
        D2Style.hint(note);
        content.addView(note);

        List<D2Changelog.Section> sections = D2Changelog.parse(read());
        if (sections.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(getString(R.string.news_none));
            D2Style.text(none);
            content.addView(none);
        }
        for (D2Changelog.Section section : sections) {
            TextView head = new TextView(this);
            head.setText(section.date.isEmpty() ? section.version : section.version + " · " + section.date);
            head.setTextSize(18);
            head.setPadding(0, dp(14), 0, dp(4));
            D2Style.heading(head);
            content.addView(head);
            StringBuilder items = new StringBuilder();
            for (String item : section.items) items.append(items.length() > 0 ? "\n" : "").append("•  ").append(item);
            TextView body = new TextView(this);
            body.setText(items.toString());
            body.setLineSpacing(0, 1.15f);
            D2Style.text(body);
            content.addView(body);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(D2Style.BACKGROUND);
        scrollView.addView(content);
        setContentView(scrollView);
    }

    private String read() {
        boolean german = getResources().getConfiguration().getLocales().get(0).getLanguage().equals("de");
        try (InputStream in = getAssets().open(german ? D2Changelog.ASSET_DE : D2Changelog.ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
