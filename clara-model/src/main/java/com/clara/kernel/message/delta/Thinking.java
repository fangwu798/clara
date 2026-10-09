package com.clara.kernel.message.delta;

import com.clara.annoation.ProviderAdapter;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ThinkingEnum;
import lombok.Data;

@Data
public class Thinking {
    private ThinkingEnum type;
}
