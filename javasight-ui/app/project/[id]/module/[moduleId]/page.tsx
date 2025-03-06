'use client';

import { useEffect, useState } from 'react';
import Breadcrumb from '@/app/components/Breadcrumb';
import PackageCard from '@/app/components/PackageCard';
import { use } from 'react';
import ReactMarkdown from 'react-markdown';

interface Package {
  _id: string;
  packageName: string;
  fileCount: number;
  linesOfCode: number;
  analysis: string;
  metrics?: {
    fileCount: number;
    linesOfCode: number;
    // add other metric fields as needed
  };
}

interface ModuleData {
  _id: string;
  moduleName: string;
  analysis: string;
  packages: Package[];
  packageCount: number;
  fileCount: number;
  linesOfCode: number;
  metrics: ModuleMetrics | null;
}

interface ModuleMetrics {
  packageCount: number;
  fileCount: number;
  linesOfCode: number;
}

export default function ModulePage({ params }: { params: Promise<{ id: string, moduleId: string }> }) {
  const resolvedParams = use(params);
  const [module, setModule] = useState<ModuleData | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showFullDescription, setShowFullDescription] = useState(false);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const moduleData = await fetch(`/api/projects/${resolvedParams.id}/modules/${resolvedParams.moduleId}`).then(res => res.json());

        setModule(moduleData);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'An error occurred');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [resolvedParams.id, resolvedParams.moduleId]);

  const truncateDescription = (text: string) => {
    if (text.length <= 100) return text;
    return `${text.slice(0, 100).trim()}... `;
  };

  if (loading) {
    return <div className="flex justify-center items-center h-screen">Loading...</div>;
  }

  if (error) {
    return <div className="text-red-500">Error: {error}</div>;
  }

  if (!module) {
    return <div>Module not found</div>;
  }

  return (
    <main className="flex-1">
      <div className="container mx-auto px-4 py-8 max-w-7xl">
        <div className="space-y-6">
        <Breadcrumb 
            projectId={resolvedParams.id}
            moduleId={resolvedParams.moduleId}
          />

          <div>
            <h1 className="text-3xl font-bold text-gray-900">{module.moduleName}</h1>
            <div className="prose prose-sm mt-2 text-gray-600 max-w-none">
              {showFullDescription ? (
                <div>
                  <ReactMarkdown>{module.analysis}</ReactMarkdown>
                  <button
                    onClick={() => setShowFullDescription(false)}
                    className="text-blue-600 hover:text-blue-800 text-sm font-medium"
                  >
                    Show less
                  </button>
                </div>
              ) : (
                <div>
                  <span>{truncateDescription(module.analysis || "")}</span>
                  {(module.analysis|| "").length > 100 && (
                    <button
                      onClick={() => setShowFullDescription(true)}
                      className="text-blue-600 hover:text-blue-800 text-sm font-medium inline"
                    >
                      more
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <h3 className="text-sm font-medium text-gray-500">Packages</h3>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{module.metrics?.packageCount.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <h3 className="text-sm font-medium text-gray-500">Files</h3>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{module.metrics?.fileCount.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <h3 className="text-sm font-medium text-gray-500">Lines of Code</h3>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{module.metrics?.linesOfCode.toLocaleString() || 0}</p>
            </div>
          </div>

          <div>
            <h2 className="text-2xl font-semibold text-gray-900 mb-4">Packages</h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {module.packages.map((pkg) => (
                <PackageCard
                  key={pkg._id}
                  name={pkg.packageName}
                  files={pkg.metrics?.fileCount || 0}
                  linesOfCode={pkg.metrics?.linesOfCode || 0}
                  description={""}
                  projectId={resolvedParams.id}
                  moduleId={resolvedParams.moduleId}
                  packageId={pkg._id}
                />
              ))}
            </div>
          </div>
        </div>
      </div>
    </main>
  );
} 