/*
 * oadx-vkprobe — does our driver run this phone's GPU? Exit 0 and print the
 * GPU's name if it does; anything else means no. "Our driver" is Turnip on a
 * phone with an Adreno GPU and RADV (the radv-xclipse port) on an Exynos with
 * an Xclipse GPU; see doc/linux-gpu.md.
 *
 * Runs inside the guest at every Linux start, before the session is told to
 * draw GL on the GPU (Zink, see linux-rt.sh). That order is the point: Mesa
 * does not fall back to the CPU when Zink cannot find a GPU, it fails the
 * app's GL outright, so the switch is only thrown once this has said yes.
 *
 * Yes means: the Vulkan loader is there, our driver enumerates a device, and
 * a logical device on it can be created and destroyed, which is the first
 * point where the kernel's GPU driver (KGSL, or Samsung's sgpu) is actually
 * asked for a GPU context.
 * The caller points VK_ICD_FILENAMES at our driver alone, so nothing else on
 * the system can answer for it.
 *
 * The loader is opened with dlopen so a guest without libvulkan1 gets a clean
 * "no" instead of a dynamic-linker error.
 */
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <vulkan/vulkan.h>

int main(void)
{
   void *lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
   if (!lib) {
      fprintf(stderr, "oadx-vkprobe: no Vulkan loader (%s)\n", dlerror());
      return 2;
   }

   PFN_vkGetInstanceProcAddr gipa =
      (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
   PFN_vkCreateInstance create_instance =
      gipa ? (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance") : NULL;
   if (!create_instance) {
      fprintf(stderr, "oadx-vkprobe: loader has no vkCreateInstance\n");
      return 2;
   }

   VkApplicationInfo app = {
      .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "oadx-vkprobe",
      .apiVersion = VK_API_VERSION_1_1,
   };
   VkInstanceCreateInfo ici = {
      .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &app,
   };
   VkInstance inst;
   VkResult r = create_instance(&ici, NULL, &inst);
   if (r != VK_SUCCESS) {
      fprintf(stderr, "oadx-vkprobe: vkCreateInstance %d\n", r);
      return 3;
   }

   PFN_vkDestroyInstance destroy_instance =
      (PFN_vkDestroyInstance)gipa(inst, "vkDestroyInstance");
   PFN_vkEnumeratePhysicalDevices enum_devices =
      (PFN_vkEnumeratePhysicalDevices)gipa(inst, "vkEnumeratePhysicalDevices");
   PFN_vkGetPhysicalDeviceProperties2 props2 =
      (PFN_vkGetPhysicalDeviceProperties2)gipa(inst, "vkGetPhysicalDeviceProperties2");
   PFN_vkGetPhysicalDeviceQueueFamilyProperties queue_props =
      (PFN_vkGetPhysicalDeviceQueueFamilyProperties)gipa(inst, "vkGetPhysicalDeviceQueueFamilyProperties");
   PFN_vkCreateDevice create_device =
      (PFN_vkCreateDevice)gipa(inst, "vkCreateDevice");
   PFN_vkDestroyDevice destroy_device =
      (PFN_vkDestroyDevice)gipa(inst, "vkDestroyDevice");
   if (!destroy_instance || !enum_devices || !props2 || !queue_props ||
       !create_device || !destroy_device) {
      fprintf(stderr, "oadx-vkprobe: instance is missing core entry points\n");
      return 3;
   }

   VkPhysicalDevice devs[8];
   uint32_t n = 8;
   r = enum_devices(inst, &n, devs);
   if ((r != VK_SUCCESS && r != VK_INCOMPLETE) || n == 0) {
      fprintf(stderr, "oadx-vkprobe: no Vulkan device (%d)\n", r);
      destroy_instance(inst, NULL);
      return 4;
   }

   int status = 5;
   for (uint32_t i = 0; i < n && status != 0; i++) {
      VkPhysicalDeviceDriverProperties drv = {
         .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES,
      };
      VkPhysicalDeviceProperties2 p = {
         .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
         .pNext = &drv,
      };
      props2(devs[i], &p);
      if (drv.driverID != VK_DRIVER_ID_MESA_TURNIP && drv.driverID != VK_DRIVER_ID_MESA_RADV)
         continue;

      /* Any queue family will do: a context is what is being asked for. */
      uint32_t families = 0;
      queue_props(devs[i], &families, NULL);
      if (families == 0)
         continue;
      float priority = 1.0f;
      VkDeviceQueueCreateInfo qci = {
         .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
         .queueFamilyIndex = 0,
         .queueCount = 1,
         .pQueuePriorities = &priority,
      };
      VkDeviceCreateInfo dci = {
         .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
         .queueCreateInfoCount = 1,
         .pQueueCreateInfos = &qci,
      };
      VkDevice dev;
      r = create_device(devs[i], &dci, NULL, &dev);
      if (r != VK_SUCCESS) {
         fprintf(stderr, "oadx-vkprobe: %s: vkCreateDevice %d\n",
                 p.properties.deviceName, r);
         status = 6;
         continue;
      }
      destroy_device(dev, NULL);
      printf("%s\n", p.properties.deviceName);
      status = 0;
   }

   destroy_instance(inst, NULL);
   if (status == 5)
      fprintf(stderr, "oadx-vkprobe: no device is driven by Turnip or RADV\n");
   return status;
}
