#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 uInvPose;

out vec3 vLocalPos;

void main() {
    // Position is in "outer" (pose-stack transformed) space; recover the model-local
    // position so the fragment shader can raymarch the SDF in its native space.
    vLocalPos = (uInvPose * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
