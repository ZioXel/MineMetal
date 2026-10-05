// mslcheck: compiles .metal files with the system Metal compiler (MTLDevice newLibraryWithSource) and reports errors.
// Build: xcrun clang -fobjc-arc -framework Metal -framework Foundation -o mslcheck mslcheck.m
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

int main(int argc, const char **argv) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) { fprintf(stderr, "no Metal device\n"); return 2; }
        MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
        options.languageVersion = MTLLanguageVersion2_4;
        int failures = 0;
        for (int i = 1; i < argc; i++) {
            NSString *path = [NSString stringWithUTF8String:argv[i]];
            NSError *error = nil;
            NSString *src = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:&error];
            if (src == nil) { printf("FAIL %s: unreadable\n", argv[i]); failures++; continue; }
            id<MTLLibrary> lib = [device newLibraryWithSource:src options:options error:&error];
            if (lib == nil) {
                NSString *msg = [[error.localizedDescription componentsSeparatedByString:@"\n"] componentsJoinedByString:@" | "];
                printf("FAIL %s: %s\n", argv[i], [msg UTF8String]);
                failures++;
            } else {
                printf("OK   %s\n", argv[i]);
            }
        }
        return failures > 0 ? 1 : 0;
    }
}
