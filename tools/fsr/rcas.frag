#version 450
#extension GL_GOOGLE_include_directive : require
#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
layout(set=0, binding=0) uniform sampler2D InputTexture;
layout(push_constant) uniform P { vec4 sizes; vec4 extra; } p; // extra.x = sharpness in stops
#define FSR_RCAS_F 1
AF4 FsrRcasLoadF(ASU2 pos) { return texelFetch(InputTexture, pos, 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}
#include "ffx_fsr1.h"
layout(location=0) out vec4 outColor;
void main() {
  AU4 con;
  FsrRcasCon(con, p.extra.x);
  AF3 c;
  FsrRcasF(c.r, c.g, c.b, AU2(gl_FragCoord.xy), con);
  outColor = vec4(c, 1.0);
}
