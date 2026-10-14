#version 450
#extension GL_GOOGLE_include_directive : require
#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
layout(set=0, binding=0) uniform sampler2D InputTexture;
layout(push_constant) uniform P { vec4 sizes; vec4 extra; } p; // sizes = inW, inH, outW, outH
#define FSR_EASU_F 1
AF4 FsrEasuRF(AF2 pos) { return textureGather(InputTexture, pos, 0); }
AF4 FsrEasuGF(AF2 pos) { return textureGather(InputTexture, pos, 1); }
AF4 FsrEasuBF(AF2 pos) { return textureGather(InputTexture, pos, 2); }
#include "ffx_fsr1.h"
layout(location=0) out vec4 outColor;
void main() {
  AU4 con0, con1, con2, con3;
  FsrEasuCon(con0, con1, con2, con3, p.sizes.x, p.sizes.y, p.sizes.x, p.sizes.y, p.sizes.z, p.sizes.w);
  AF3 c;
  FsrEasuF(c, AU2(gl_FragCoord.xy), con0, con1, con2, con3);
  outColor = vec4(c, 1.0);
}
