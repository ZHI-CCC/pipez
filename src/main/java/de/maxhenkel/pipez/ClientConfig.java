package de.maxhenkel.pipez;

import de.maxhenkel.corelib.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

public class ClientConfig extends ConfigBase {

    public final ModConfigSpec.BooleanValue rememberLastTab;

    public ClientConfig(ModConfigSpec.Builder builder) {
        super(builder);

        rememberLastTab = builder
                .comment("记住每个管道连接最后选择的传输类型标签页", "数据仅保存在客户端 config 文件夹中")
                .define("remember_last_tab", true);
    }

}
