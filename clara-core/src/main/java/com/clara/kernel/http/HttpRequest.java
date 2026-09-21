package com.clara.kernel.http;

import com.clara.context.ConvContext;
import com.clara.kernel.api.IRequest;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.message.ImageParameters;
import com.clara.utils.deserialize.ProviderRequestEncoder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class HttpRequest extends ImageParameters {

    @Override
    public <T extends IRequest> Class<T> getCls() {
        return (Class<T>) HttpRequest.class;
    }

    @Override
    public RawHttpRequest toRequest() {
        ProviderConfiguration providerConfiguration = ConvContext.current().getProviderConfiguration();
        return ProviderRequestEncoder.encode(this, providerConfiguration);
    }
}
