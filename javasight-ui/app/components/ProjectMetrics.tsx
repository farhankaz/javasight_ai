'use client';

import { useEffect, useState } from 'react';
import { DocumentDuplicateIcon, CodeBracketIcon, SparklesIcon, CubeIcon, FolderIcon } from '@heroicons/react/24/outline';


interface Metrics {
    moduleCount?: number;
    packageCount?: number;
    fileCount?: number;
    linesOfCode?: number;
    combinedAnalysisTokenCount?: number;
    combinedCodeTokenCount?: number;
}

interface ProjectMetricsProps {
    metrics: Metrics | null;
}

export default function ProjectMetrics({ metrics }: ProjectMetricsProps) {
    if (!metrics) {
        return null;
    }

    return (
        <div className="grid grid-cols-1 md:grid-cols-6 gap-4 mb-6">
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <CubeIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Modules</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{metrics.moduleCount?.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <FolderIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Packages</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{metrics.packageCount?.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <DocumentDuplicateIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Files</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{metrics.fileCount?.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <CodeBracketIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Lines of Code</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{metrics.linesOfCode?.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <SparklesIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Analysis AI Tokens</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{metrics.combinedAnalysisTokenCount?.toLocaleString() || 0}</p>
            </div>
            <div className="bg-white rounded-lg p-6 shadow-sm">
              <div className="flex items-center gap-1">
                <SparklesIcon className="h-5 w-5 text-gray-500" />
                <h3 className="text-sm font-medium text-gray-500">Code AI Tokens</h3>
              </div>
              <p className="mt-2 text-3xl font-bold text-indigo-600">{metrics.combinedCodeTokenCount?.toLocaleString() || 0}</p>
            </div>                        
        </div>
    )
}
