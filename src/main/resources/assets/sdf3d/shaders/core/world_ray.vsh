#version 150

in vec3 Position;

out vec2 vNdc;

void main() {
    vNdc = Position.xy;
    gl_Position = vec4(Position, 1.0);
}
