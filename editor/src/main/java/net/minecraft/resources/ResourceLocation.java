package net.minecraft.resources;

import java.util.Objects;

/**
 * Minimal stand-alone stand-in for Minecraft's {@code ResourceLocation}, so the shared
 * {@code mods.hexagon.sdf3d.sdf} core (parser/graph/evaluator) compiles and runs without the
 * game. Only the methods the SDF core actually uses are implemented.
 */
public final class ResourceLocation {
    private final String namespace;
    private final String path;

    private ResourceLocation(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static ResourceLocation parse(String location) {
        int colon = location.indexOf(':');
        if (colon < 0) return new ResourceLocation("minecraft", location);
        return new ResourceLocation(location.substring(0, colon), location.substring(colon + 1));
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResourceLocation other)) return false;
        return namespace.equals(other.namespace) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }
}
