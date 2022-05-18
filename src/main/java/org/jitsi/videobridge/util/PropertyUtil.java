package org.jitsi.videobridge.util;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import javax.xml.ws.Holder;
import java.util.concurrent.TimeUnit;

public class PropertyUtil {
    private static final Logger logger = new LoggerImpl(PropertyUtil.class.getName());

    private final static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(TaskPools.IO_POOL))
            .callTimeout(5, TimeUnit.SECONDS)
            .build();

    public static String getValue(String localSystemProp, String gcloudInstanceAttr) {
        Holder<String> propertyValueHolder = new Holder<>(System.getProperty(localSystemProp));

        if (StringUtils.isBlank(propertyValueHolder.value)) {
            logger.info("System property `" + localSystemProp + "` is not set. Trying to get it from google instance metadata");
            Request request = new Request.Builder()
                    .addHeader("Metadata-Flavor", "Google")
                    .url("http://metadata.google.internal/computeMetadata/v1/instance/attributes/" + gcloudInstanceAttr)
                    .get().build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    propertyValueHolder.value = response.body().string();
                    logger.info("Google instance attribute `" + gcloudInstanceAttr + "` value is: " + propertyValueHolder.value);
                } else {
                    String msg = String.format(
                            "Can not get `%s` from google cloud metadata. Response(code=%s,message=%s)",
                            gcloudInstanceAttr,
                            response.code(),
                            response.body() == null ? "" : response.body().string()
                    );
                    logger.error(msg);
                }
            } catch (Exception e) {
                logger.error("Can not get `" + gcloudInstanceAttr + "` from google cloud metadata", e);
            }
        } else {
            logger.info("System property `" + localSystemProp + "` value is: " + propertyValueHolder.value);
        }
        return propertyValueHolder.value;
    }
}
