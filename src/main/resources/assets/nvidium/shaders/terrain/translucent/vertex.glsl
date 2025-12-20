#version 460
#extension GL_ARB_shading_language_include : enable

#import <amdidium:occlusion/scene.glsl>
#import <amdidium:terrain/fog.glsl>
#import <amdidium:terrain/vertex_format.glsl>

layout(location = 0) in uvec4 packedVertex;

out vec4 vColor;
out vec2 vUV;
out vec2 vLightUV;
#ifdef RENDER_FOG
out float fogLerp;
#endif

uniform mat4 MVP;
uniform mat4 transformationArray[MAX_TRANSFORMS];
uniform vec3 origin;
uniform vec3 subchunkOffset;

void main() {
    Vertex v = Vertex(packedVertex.x, packedVertex.y, packedVertex.z, packedVertex.w);

    vec3 pos = decodeVertexPosition(v) + origin
