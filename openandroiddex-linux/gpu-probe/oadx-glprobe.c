/*
 * oadx-glprobe — does GL in this guest reach the phone's GPU through VirGL?
 * Exit 0 and print the renderer if it does; 2 means this guest has no Mesa
 * GL yet (no GL app installed, nothing to check); anything else means no.
 * See doc/linux-gpu.md, "VirGL: any other GPU".
 *
 * On a phone whose GPU no Mesa driver of ours runs (Mali, PowerVR, an Adreno
 * older than the 600 series), the app runs virglrenderer's server on the
 * Android side, on the phone's own GLES driver, listening on the guest's
 * /tmp/.virgl_test. Mesa's "virpipe" driver in the guest sends GL to it.
 * Mesa does not fall back when that server cannot be reached: an app gets no
 * GL at all. So a session only points GL at it once this has said yes.
 *
 * The caller sets GALLIUM_DRIVER=virpipe and LIBGL_ALWAYS_SOFTWARE=1, which
 * is how GL in an Xvnc session ends up too (no DRI3, so Mesa's software
 * path, whose driver GALLIUM_DRIVER picks). Yes means: a GL context was made
 * current and its renderer is VirGL's, not llvmpipe's, which is what a
 * context gets when the variable is not honoured.
 *
 * Asked through GLX on the session's own display (DISPLAY, and XAUTHORITY
 * where the display has one), the way the session's apps will draw, which
 * the probe waits for while Xvnc starts; through EGL on Mesa's surfaceless
 * platform only without a display or without libGL. An XFCE desktop alone
 * brings libGL.so.1 and not libEGL.so.1 (a Redmi Note 7's fresh guest,
 * 2026-11-22), and through VirGL the surfaceless platform there offered no
 * config while GLX drew. Every library is opened with dlopen, so a guest
 * without them gets a clean "2".
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <X11/Xlib.h>
#include <GL/glx.h>

#define OADX_GL_RENDERER 0x1F01
#define OADX_GL_VERSION 0x1F02
typedef const unsigned char *(*oadx_glGetString)(unsigned int);

#define LOAD(type, name) type name = (type)gpa(#name)
#define SYM(lib, type, name) type name##_ = (type)dlsym(lib, #name)

/* The answer for a renderer string: VirGL's, or not. */
static int verdict(const char *renderer, const char *version, const char *how)
{
   int ok = renderer && strstr(renderer, "virgl") != NULL;
   if (ok) {
      printf("%.160s, GL %.40s (%s)\n", renderer, version ? version : "?", how);
   } else {
      fprintf(stderr, "not VirGL (%s): %.160s\n", how, renderer ? renderer : "no renderer");
   }
   return ok ? 0 : 3;
}

/* Mesa's software path reads the window back (XGetImage) when a context is made
 * current on it, which X refuses for a window never mapped (BadMatch: glxinfo
 * died of exactly that through VirGL). Xlib's default handler would end the
 * probe there; the context is fine, so the error is let pass. */
static int ignore_x_error(Display *dpy, XErrorEvent *e)
{
   (void)dpy;
   (void)e;
   return 0;
}

static int glx_probe(void)
{
   void *x11 = dlopen("libX11.so.6", RTLD_NOW | RTLD_LOCAL);
   void *gl = x11 ? dlopen("libGL.so.1", RTLD_NOW | RTLD_LOCAL) : NULL;
   if (!x11 || !gl) {
      fprintf(stderr, "no Mesa GL in this guest (%s)\n", dlerror());
      return 2;
   }
   /* One name per type: a type with a comma in it would split the macro's arguments. */
   typedef Display *(*t_XOpenDisplay)(const char *);
   typedef int (*t_XCloseDisplay)(Display *);
   typedef Window (*t_XRootWindow)(Display *, int);
   typedef int (*t_XDefaultScreen)(Display *);
   typedef Colormap (*t_XCreateColormap)(Display *, Window, Visual *, int);
   typedef Window (*t_XCreateWindow)(Display *, Window, int, int, unsigned int, unsigned int, unsigned int,
                                     int, unsigned int, Visual *, unsigned long, XSetWindowAttributes *);
   typedef int (*t_XDestroyWindow)(Display *, Window);
   typedef int (*t_XFreeColormap)(Display *, Colormap);
   typedef int (*t_XFree)(void *);
   typedef XVisualInfo *(*t_glXChooseVisual)(Display *, int, int *);
   typedef GLXContext (*t_glXCreateContext)(Display *, XVisualInfo *, GLXContext, Bool);
   typedef Bool (*t_glXMakeCurrent)(Display *, GLXDrawable, GLXContext);
   typedef void (*t_glXDestroyContext)(Display *, GLXContext);
   typedef XErrorHandler (*t_XSetErrorHandler)(XErrorHandler);
   SYM(x11, t_XSetErrorHandler, XSetErrorHandler);
   SYM(x11, t_XOpenDisplay, XOpenDisplay);
   SYM(x11, t_XCloseDisplay, XCloseDisplay);
   SYM(x11, t_XRootWindow, XRootWindow);
   SYM(x11, t_XDefaultScreen, XDefaultScreen);
   SYM(x11, t_XCreateColormap, XCreateColormap);
   SYM(x11, t_XCreateWindow, XCreateWindow);
   SYM(x11, t_XDestroyWindow, XDestroyWindow);
   SYM(x11, t_XFreeColormap, XFreeColormap);
   SYM(x11, t_XFree, XFree);
   SYM(gl, t_glXChooseVisual, glXChooseVisual);
   SYM(gl, t_glXCreateContext, glXCreateContext);
   SYM(gl, t_glXMakeCurrent, glXMakeCurrent);
   SYM(gl, t_glXDestroyContext, glXDestroyContext);
   SYM(gl, oadx_glGetString, glGetString);
   if (!XSetErrorHandler_ || !XOpenDisplay_ || !XCloseDisplay_ || !XRootWindow_ || !XDefaultScreen_ || !XCreateColormap_
       || !XCreateWindow_ || !XDestroyWindow_ || !XFreeColormap_ || !XFree_ || !glXChooseVisual_
       || !glXCreateContext_ || !glXMakeCurrent_ || !glXDestroyContext_ || !glGetString_) {
      fprintf(stderr, "libX11 or libGL lacks the calls this probe needs\n");
      return 3;
   }

   XSetErrorHandler_(ignore_x_error);
   /* The session starts Xvnc right after this probe: up to ten seconds for it. */
   Display *dpy = NULL;
   for (int i = 0; i < 50 && !dpy; i++) {
      dpy = XOpenDisplay_(NULL);
      if (!dpy) usleep(200000);
   }
   if (!dpy) {
      fprintf(stderr, "no X display at %s\n", getenv("DISPLAY") ? getenv("DISPLAY") : "(DISPLAY unset)");
      return 3;
   }
   int want[] = { GLX_RGBA, GLX_RED_SIZE, 1, GLX_GREEN_SIZE, 1, GLX_BLUE_SIZE, 1, None };
   XVisualInfo *vi = glXChooseVisual_(dpy, XDefaultScreen_(dpy), want);
   if (!vi) {
      fprintf(stderr, "no GLX visual\n");
      XCloseDisplay_(dpy);
      return 3;
   }
   Window root = XRootWindow_(dpy, vi->screen);
   XSetWindowAttributes swa;
   memset(&swa, 0, sizeof swa);
   swa.colormap = XCreateColormap_(dpy, root, vi->visual, AllocNone);
   /* 1x1 and never mapped: a drawable to be current on, nothing on screen. */
   Window win = XCreateWindow_(dpy, root, 0, 0, 1, 1, 0, vi->depth, InputOutput, vi->visual,
                               CWColormap, &swa);
   GLXContext ctx = glXCreateContext_(dpy, vi, NULL, True);
   int rc;
   if (!ctx || !glXMakeCurrent_(dpy, win, ctx)) {
      fprintf(stderr, "no GLX context made current\n");
      rc = 3;
   } else {
      rc = verdict((const char *)glGetString_(OADX_GL_RENDERER),
                   (const char *)glGetString_(OADX_GL_VERSION), "GLX");
      glXMakeCurrent_(dpy, None, NULL);
   }
   if (ctx) glXDestroyContext_(dpy, ctx);
   XDestroyWindow_(dpy, win);
   XFreeColormap_(dpy, swa.colormap);
   XFree_(vi);
   XCloseDisplay_(dpy);
   return rc;
}

int main(void)
{
   /* In an X session GLX is what the apps use, so that is what is asked; EGL
    * on the surfaceless platform only where there is no display or no libGL.
    * Through VirGL on a Redmi Note 7 the surfaceless platform offered no
    * config at all (2026-11-22) while GLX on the session's display drew. */
   const char *display = getenv("DISPLAY");
   if (display && *display) {
      int rc = glx_probe();
      if (rc != 2) return rc;
   }
   void *lib = dlopen("libEGL.so.1", RTLD_NOW | RTLD_LOCAL);
   if (!lib) {
      fprintf(stderr, "no Mesa GL in this guest\n");
      return 2;
   }
   PFNEGLGETPROCADDRESSPROC gpa = (PFNEGLGETPROCADDRESSPROC)dlsym(lib, "eglGetProcAddress");
   if (!gpa) {
      fprintf(stderr, "libEGL has no eglGetProcAddress\n");
      return 3;
   }
   LOAD(PFNEGLGETPLATFORMDISPLAYEXTPROC, eglGetPlatformDisplayEXT);
   LOAD(PFNEGLINITIALIZEPROC, eglInitialize);
   LOAD(PFNEGLBINDAPIPROC, eglBindAPI);
   LOAD(PFNEGLCHOOSECONFIGPROC, eglChooseConfig);
   LOAD(PFNEGLCREATECONTEXTPROC, eglCreateContext);
   LOAD(PFNEGLMAKECURRENTPROC, eglMakeCurrent);
   LOAD(PFNEGLDESTROYCONTEXTPROC, eglDestroyContext);
   LOAD(PFNEGLTERMINATEPROC, eglTerminate);
   LOAD(PFNEGLGETERRORPROC, eglGetError);
   if (!eglGetPlatformDisplayEXT || !eglInitialize || !eglBindAPI || !eglChooseConfig
       || !eglCreateContext || !eglMakeCurrent || !eglDestroyContext || !eglTerminate || !eglGetError) {
      fprintf(stderr, "libEGL lacks the calls this probe needs\n");
      return 3;
   }

   EGLDisplay dpy = eglGetPlatformDisplayEXT(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
   EGLint major = 0, minor = 0;
   if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, &major, &minor)) {
      fprintf(stderr, "no surfaceless EGL display (0x%x)\n", (unsigned)eglGetError());
      return 3;
   }

   EGLint api_bit = EGL_OPENGL_BIT;
   if (!eglBindAPI(EGL_OPENGL_API)) {
      api_bit = EGL_OPENGL_ES2_BIT;
      eglBindAPI(EGL_OPENGL_ES_API);
   }
   const EGLint want[] = { EGL_RENDERABLE_TYPE, api_bit, EGL_NONE };
   EGLConfig cfg = NULL;
   EGLint n = 0;
   if (!eglChooseConfig(dpy, want, &cfg, 1, &n) || n < 1) {
      fprintf(stderr, "no EGL config (0x%x)\n", (unsigned)eglGetError());
      eglTerminate(dpy);
      return 3;
   }
   const EGLint es2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
   EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT,
                                     api_bit == EGL_OPENGL_BIT ? NULL : es2);
   if (ctx == EGL_NO_CONTEXT) {
      fprintf(stderr, "no GL context (0x%x)\n", (unsigned)eglGetError());
      eglTerminate(dpy);
      return 3;
   }
   if (!eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
      fprintf(stderr, "GL context not made current (0x%x)\n", (unsigned)eglGetError());
      eglDestroyContext(dpy, ctx);
      eglTerminate(dpy);
      return 3;
   }

   oadx_glGetString get_string = (oadx_glGetString)gpa("glGetString");
   int rc = verdict(get_string ? (const char *)get_string(OADX_GL_RENDERER) : NULL,
                    get_string ? (const char *)get_string(OADX_GL_VERSION) : NULL, "EGL");

   eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
   eglDestroyContext(dpy, ctx);
   eglTerminate(dpy);
   return rc;
}
