'use client';

import { CubeIcon, ChatBubbleLeftIcon } from '@heroicons/react/24/outline';
import { DocumentIcon, CodeBracketIcon } from '@heroicons/react/24/outline';
import { useRouter } from 'next/navigation';
import ReactMarkdown from 'react-markdown';
import { useState, useEffect } from 'react';
import { formatNumber } from '@/app/utils/formatters'


interface ModuleCardProps {
  title: string;
  description?: string;
  projectId: string;
  moduleId: string;
  packageCount: number;
  fileCount: number;
  linesOfCode: number;
}

export default function ModuleCard({ 
  title, 
  description = '',
  projectId,
  moduleId,
  packageCount,
  fileCount,
  linesOfCode
}: ModuleCardProps) {
  const router = useRouter();
  const [showFullDescription, setShowFullDescription] = useState(false);

  const handleClick = () => {
    router.push(`/project/${projectId}/module/${moduleId}`);
  };

  const truncateDescription = (text: string = '') => {
    if (!text || text.length <= 100) return text || '';
    return `${text.slice(0, 100).trim()}... `;
  };

  const formattedValue = formatNumber(packageCount)

  return (
    <div 
      className="bg-white rounded-lg p-6 shadow-sm cursor-pointer hover:shadow-md transition-shadow"
      onClick={handleClick}
    >
      <div className="flex justify-between items-start mb-4">
        <div>
          <div className="flex items-center gap-2">
            <CubeIcon className="w-6 h-6 text-indigo-500" />
            <h3 className="text-xl font-semibold text-gray-900">{title}</h3>
          </div>
          <div className="prose prose-sm max-w-none mt-1 text-gray-500">
            {showFullDescription ? (
              <div>
                <ReactMarkdown>{description}</ReactMarkdown>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setShowFullDescription(false);
                  }}
                  className="text-blue-600 hover:text-blue-800 text-sm font-medium"
                >
                  Show less
                </button>
              </div>
            ) : (
              <div>
                <span>{truncateDescription(description)}</span>
                {description.length > 100 && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setShowFullDescription(true);
                    }}
                    className="text-blue-600 hover:text-blue-800 text-sm font-medium inline"
                  >
                    more
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
        <button 
          className="text-gray-400 hover:text-gray-600"
          onClick={(e) => e.stopPropagation()}
        >
          <ChatBubbleLeftIcon className="w-5 h-5" />
        </button>
      </div>
      
      <div className="space-y-1.5">
        <div className="flex items-center text-gray-600 text-sm">
          <CubeIcon className="w-4 h-4 mr-2" />
          <span>{formattedValue} packages</span>
        </div>
        <div className="flex items-center text-gray-600 text-sm">
          <DocumentIcon className="w-4 h-4 mr-2" />
          <span>{formatNumber(fileCount)} files</span>
        </div>
        <div className="flex items-center text-gray-600 text-sm">
          <CodeBracketIcon className="w-4 h-4 mr-2" />
          <span>{formatNumber(linesOfCode)} lines of code</span>
        </div>
      </div>
    </div>
  );
} 