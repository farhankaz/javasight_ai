'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { HomeIcon } from '@heroicons/react/24/outline';

interface BreadcrumbProps {
  projectId?: string;
  moduleId?: string;
  packageId?: string;
}

interface ProjectData {
  projectName: string;
}

interface ModuleData {
  moduleName: string;
  projectId: string;
}

interface PackageData {
  packageName: string;
  module_id: string;
  project_id: string;
}

interface BreadcrumbItem {
  label: string;
  href: string;
}

export default function Breadcrumb({ projectId, moduleId, packageId }: BreadcrumbProps) {
  const [projectData, setProjectData] = useState<ProjectData | null>(null);
  const [moduleData, setModuleData] = useState<ModuleData | null>(null);
  const [packageData, setPackageData] = useState<PackageData | null>(null);

  useEffect(() => {
    async function fetchData() {
      try {
        // If we have a packageId, fetch package data first
        if (packageId) {
          const packageRes = await fetch(`/api/projects/${projectId}/modules/${moduleId}/packages/${packageId}`);
          if (packageRes.ok) {
            const packageData = await packageRes.json();
            setPackageData(packageData);
            console.log(packageData);
            
            // Update moduleId and projectId based on package data
            const mId = packageData.moduleId;
            const pId = packageData.projectId;
            
            // Fetch module data
            const moduleRes = await fetch(`/api/projects/${pId}/modules/${mId}`);
            if (moduleRes.ok) {
              setModuleData(await moduleRes.json());
            }
            
            // Fetch project data
            const projectRes = await fetch(`/api/projects/${pId}`);
            if (projectRes.ok) {
              setProjectData(await projectRes.json());
            }
          }
        }
        // If we have moduleId but no packageId
        else if (moduleId && projectId) {
          const moduleRes = await fetch(`/api/projects/${projectId}/modules/${moduleId}`);
          if (moduleRes.ok) {
            const moduleData = await moduleRes.json();
            console.log(moduleData);
            setModuleData(moduleData);
          }
          
          const projectRes = await fetch(`/api/projects/${projectId}`);
          if (projectRes.ok) {
            setProjectData(await projectRes.json());
          }
        }
        // If we only have projectId
        else if (projectId) {
          const projectRes = await fetch(`/api/projects/${projectId}`);
          if (projectRes.ok) {
            setProjectData(await projectRes.json());
          }
        }
      } catch (error) {
        console.error('Error fetching breadcrumb data:', error);
      }
    }

    fetchData();
  }, [projectId, moduleId, packageId]);

  const breadcrumbItems: BreadcrumbItem[] = [];

  if (projectData) {
    breadcrumbItems.push({
      label: projectData.projectName,
      href: `/project/${projectId}`
    });
  }

  if (moduleData) {
    breadcrumbItems.push({
      label: moduleData.moduleName,
      href: `/project/${projectId}/module/${moduleId}`
    });
  }

  if (packageData) {
    breadcrumbItems.push({
      label: packageData.packageName,
      href: `/project/${projectId}/module/${moduleId}/package/${packageId}`
    });
  }

  return (
    <nav className="flex items-center space-x-2 text-gray-600">
      <Link href="/" className="hover:text-gray-900">
        <HomeIcon className="w-5 h-5" />
      </Link>
      {breadcrumbItems.map((item, index) => (
        <div key={index} className="flex items-center">
          <span className="mx-2 text-gray-400">/</span>
          {item.href ? (
            <Link href={item.href} className="hover:text-gray-900">
              {item.label}
            </Link>
          ) : (
            <span className="text-gray-900">{item.label}</span>
          )}
        </div>
      ))}
    </nav>
  );
} 