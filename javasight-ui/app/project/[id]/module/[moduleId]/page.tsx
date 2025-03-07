'use client';

import { useEffect, useState, use } from 'react';
import Breadcrumb from '@/app/components/Breadcrumb';
import Link from 'next/link';
import ReactMarkdown from 'react-markdown';
import { toast } from 'react-hot-toast';
import { FolderIcon } from '@heroicons/react/24/outline';

interface Package {
  _id: string;
  packageName: string;
  fileCount: number;
  linesOfCode: number;
  analysis: string;
  metrics?: {
    fileCount: number;
    linesOfCode: number;
    codeTokenCount?: number;
    combinedAnalysisTokenCount?: number;
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
  combinedAnalysisTokenCount?: number;
  combinedCodeTokenCount?: number;
}
export default function ModulePage({ params }: { params: Promise<{ id: string, moduleId: string }> }) {
  const resolvedParams = use(params);
  const [module, setModule] = useState<ModuleData | null>(null);

  const [loading, setLoading] = useState(true);
  const [analyzing, setAnalyzing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showFullDescription, setShowFullDescription] = useState(true);
  const [analyzeSuccess, setAnalyzeSuccess] = useState<string | null>(null);
  const [analyzeError, setAnalyzeError] = useState<string | null>(null);

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

  const triggerModuleAnalysis = async () => {
    try {
      setAnalyzing(true);
      setAnalyzeSuccess(null);
      setAnalyzeError(null);
      
      const response = await fetch(`/api/projects/${resolvedParams.id}/modules/${resolvedParams.moduleId}/analyze`, {
        method: 'POST',
      });
      
      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Failed to analyze module');
      }
      
      const data = await response.json();
      setAnalyzeSuccess('Module analysis successfully requested. Results will be available once the analysis completes.');
      
      // Refresh the module data after a delay to get the updated analysis
      setTimeout(() => {
        fetchData();
      }, 10000); // 10 second delay to allow time for analysis to start
      
    } catch (err) {
      setAnalyzeError(err instanceof Error ? err.message : 'Failed to trigger module analysis');
    } finally {
      setAnalyzing(false);
    }
  };

  const fetchData = async () => {
    try {
      const moduleData = await fetch(`/api/projects/${resolvedParams.id}/modules/${resolvedParams.moduleId}`).then(res => res.json());
      setModule(moduleData);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    }
  };

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

          <div className="flex justify-between items-start">
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
            <div>
              <button 
                onClick={triggerModuleAnalysis}
                disabled={analyzing}
                className={`px-4 py-2 rounded-md ${analyzing ? 'bg-gray-400' : 'bg-indigo-600 hover:bg-indigo-700'} text-white font-medium flex items-center`}
              >
                {analyzing ? (
                  <>
                    <svg className="animate-spin -ml-1 mr-2 h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                    </svg>
                    Analyzing...
                  </>
                ) : (
                  'Analyze Module'
                )}
              </button>
              
              {analyzeSuccess && (
                <div className="mt-2 text-sm text-green-600">{analyzeSuccess}</div>
              )}
              
              {analyzeError && (
                <div className="mt-2 text-sm text-red-600">Error: {analyzeError}</div>
              )}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
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
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <h3 className="text-sm font-medium text-gray-500">Analysis Tokens</h3>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{module.metrics?.combinedAnalysisTokenCount?.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <h3 className="text-sm font-medium text-gray-500">Code Tokens</h3>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{module.metrics?.combinedCodeTokenCount?.toLocaleString() || 0}</p>
            </div>                        
          </div>

          <div className="bg-white rounded-lg shadow-sm p-6">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead>
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Package Name</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Files</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Lines of Code</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Analysis Tokens</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Code Tokens</th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Description</th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {module.packages.map((pkg) => (
                    <tr key={pkg._id}>
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">
                        <Link
                          href={`/project/${resolvedParams.id}/module/${resolvedParams.moduleId}/package/${pkg._id}`}
                          className="flex items-center text-indigo-600 hover:text-indigo-800"
                        >
                          <FolderIcon className="w-4 h-4 mr-2" />
                          {pkg.packageName}
                        </Link>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{pkg.metrics?.fileCount || 0}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{pkg.metrics?.linesOfCode || 0}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{pkg.metrics?.combinedAnalysisTokenCount || 0 || 0}</td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{pkg.metrics?.codeTokenCount || 0}</td>
                      <td className="px-6 py-4 text-sm text-gray-500">{pkg.analysis}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}