package org.cloudburstmc.proxypass.network.bedrock.util;

import lombok.experimental.UtilityClass;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@UtilityClass
public class ItemDefinitionRegistries {
    public static DefinitionRegistry<ItemDefinition> fromDefinitions(Collection<? extends ItemDefinition> definitions) {
        SimpleDefinitionRegistry.Builder<ItemDefinition> builder = SimpleDefinitionRegistry.builder();
        Set<Integer> runtimeIds = new HashSet<>();

        add(builder, runtimeIds, ItemDefinition.AIR);
        for (ItemDefinition definition : definitions) {
            add(builder, runtimeIds, definition);
        }

        return builder.build();
    }

    public static DefinitionRegistry<ItemDefinition> empty() {
        return SimpleDefinitionRegistry.<ItemDefinition>builder()
                .add(ItemDefinition.AIR)
                .build();
    }

    private static void add(SimpleDefinitionRegistry.Builder<ItemDefinition> builder, Set<Integer> runtimeIds, ItemDefinition definition) {
        if (runtimeIds.add(definition.getRuntimeId())) {
            builder.add(definition);
        }
    }
}
